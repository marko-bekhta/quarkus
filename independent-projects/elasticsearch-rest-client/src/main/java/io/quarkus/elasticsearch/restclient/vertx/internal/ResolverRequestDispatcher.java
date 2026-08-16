package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.ResponseException;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.WarningFailureException;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.AddressResolver;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.endpoint.LoadBalancer;
import io.vertx.core.net.endpoint.ServerEndpoint;
import io.vertx.core.net.endpoint.ServerSelector;
import io.vertx.core.spi.endpoint.EndpointBuilder;
import io.vertx.core.spi.endpoint.EndpointResolver;

/**
 * {@link io.quarkus.elasticsearch.restclient.vertx.RequestDispatcher} that integrates
 * with the Vert.x {@link AddressResolver} and {@link LoadBalancer} SPIs. Requests are
 * routed through a resolver-backed {@link HttpClient} that handles node resolution and
 * load balancing, while this dispatcher manages retry logic and dead-node tracking.
 * <p>
 * Node identity is preserved through the SPI via {@link ElasticsearchServer}, which
 * pairs a {@link NodeImpl} with its {@link SocketAddress}. The load balancer's
 * {@link ServerEndpoint#unwrap()} returns the {@code ElasticsearchServer}, giving
 * the selector direct access to the logical node without fragile index correlation.
 */
public class ResolverRequestDispatcher extends AbstractRequestDispatcher {

    private final AtomicInteger nodeListVersion = new AtomicInteger(0);
    private final HttpConstants.Scheme scheme;
    private final WarningsHandler defaultWarningsHandler;

    public ResolverRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            List<NodeImpl> initialNodes, NodeDiscoveryConfigurer nodeDiscoveryConfigurer,
            VertxElasticsearchClient client, HttpConstants.Scheme scheme,
            Vertx vertx, HttpClientOptions httpClientOptions, PoolOptions poolOptions,
            BackoffStrategy backoffStrategy) {
        super(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler,
                initialNodes, nodeDiscoveryConfigurer, client, scheme, vertx,
                httpClientOptions, poolOptions, backoffStrategy);
        this.scheme = scheme;
        this.defaultWarningsHandler = defaultWarningsHandler != null ? defaultWarningsHandler : WarningsHandler.PERMISSIVE;
    }

    // Test-only: creates a resolver-backed HttpClient using the provided Vert.x instance.
    public ResolverRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            Vertx vertx) {
        super(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler);
        this.scheme = HttpConstants.Scheme.HTTP;
        this.defaultWarningsHandler = defaultWarningsHandler != null ? defaultWarningsHandler : WarningsHandler.PERMISSIVE;
        // The test-only super constructor takes no Vertx and so leaves httpClient null; build
        // the resolver-backed client here instead.
        this.httpClient = createHttpClient(vertx, null, null);
    }

    @Override
    protected HttpClient createHttpClient(Vertx vertx, HttpClientOptions options, PoolOptions poolOptions) {
        PoolOptions pool = poolOptions != null ? poolOptions : new PoolOptions();
        return vertx.httpClientBuilder()
                .withAddressResolver(addressResolver())
                .withLoadBalancer(loadBalancer())
                .with(withTransportDefaults(options))
                .with(pool)
                .build();
    }

    @Override
    protected void onNodesUpdated() {
        this.nodeListVersion.incrementAndGet();
    }

    @Override
    public CancellableFuture<Response> dispatch(Request request) {
        Promise<Response> promise = Promise.promise();
        CancellableFuture<Response> cancellable = new CancellableFuture<>(promise.future());

        List<NodeImpl> currentNodes = this.routableNodes;
        if (currentNodes.isEmpty()) {
            promise.fail(noRoutableNodesException());
            return cancellable;
        }

        int maxRetries = currentNodes.size();
        sendViaResolver(request, null, maxRetries, cancellable)
                .onComplete(promise);
        return cancellable;
    }

    private Future<Response> sendViaResolver(Request request, Throwable previousException,
            int retriesLeft, CancellableFuture<Response> cancellable) {
        if (retriesLeft <= 0) {
            return Future.failedFuture(previousException != null
                    ? previousException
                    : new IOException("All nodes failed"));
        }
        if (cancellable.isCancelled()) {
            return Future.failedFuture(new CancellationException());
        }

        RequestOptions options = new RequestOptions()
                .setServer(ElasticsearchAddress.INSTANCE)
                .setMethod(HttpMethod.valueOf(request.getMethod()))
                .setURI(buildUri(request))
                .setSsl(scheme.isSsl());

        // TODO: The Vert.x endpoint SPI does not expose the selected ServerEndpoint on the
        //  HttpClientRequest or HttpClientResponse. The selected endpoint (and our
        //  ElasticsearchServer with its NodeImpl) is consumed internally by HttpClientImpl
        //  and never surfaced. httpRequest.authority() is also null in the resolver path.
        //  We recover node identity by matching connection().remoteAddress() against the
        //  node list. This works when nodes are configured with IP addresses but can fail
        //  when hostnames resolve to different IPs.
        //  Two possible improvements to discuss:
        //  1. Expose the selected ServerEndpoint (or its unwrap() result) on
        //     HttpClientRequest, so dispatchers can identify which logical server was chosen.
        //  2. Allow the application to report HTTP-status-based failures through the
        //     InteractionMetrics/ServerInteraction SPI (e.g. treating 502/503/504 as
        //     failures), so dead-node tracking could be handled entirely within the LB layer.
        return httpClient.request(options)
                .compose(httpRequest -> {
                    cancellable.setCurrentRequest(httpRequest);
                    applyHeaders(httpRequest, request);
                    if (request.getBody() != null) {
                        return httpRequest.send(request.getBody());
                    }
                    return httpRequest.send();
                })
                .compose(httpResponse -> httpResponse.body().map(body -> {
                    SocketAddress remote = httpResponse.request().connection().remoteAddress();
                    NodeImpl node = findNodeByRemoteAddress(remote);
                    URI nodeUri = node != null ? node.getHost()
                            : (remote != null
                                    ? URI.create(scheme.value + "://" + remote.host() + ":" + remote.port())
                                    : null);
                    return new NodeResponse(
                            new Response(httpResponse.statusCode(), httpResponse.statusMessage(),
                                    httpResponse.headers(), body, nodeUri, request.getMethod()),
                            node);
                }))
                .compose(
                        nodeResponse -> {
                            if (cancellable.isCancelled()) {
                                // Cancelled after the response arrived: do not deliver it
                                // (nor a ResponseException), and do not retry.
                                return Future.failedFuture(new CancellationException());
                            }
                            Response response = nodeResponse.response;
                            NodeImpl node = nodeResponse.node;
                            int statusCode = response.getStatusCode();

                            if (statusCode < 500) {
                                if (node != null) {
                                    markAlive(node);
                                }
                                WarningsHandler handler = request.getWarningsHandler() != null
                                        ? request.getWarningsHandler()
                                        : defaultWarningsHandler;
                                if (handler != WarningsHandler.PERMISSIVE
                                        && handler.shouldFail(response.getWarnings())) {
                                    return Future.failedFuture(new WarningFailureException(response));
                                }
                                return Future.succeededFuture(response);
                            } else if (HttpConstants.isRetryableStatus(statusCode)) {
                                if (node != null) {
                                    markDead(node);
                                }
                                // No nodeListVersion bump here: the node set itself is unchanged,
                                // only this node's liveness. DeadNodeAwareSelector consults dead
                                // state live on every select(), so the cached endpoint stays valid
                                // and skips the dead node without a global resolver-cache rebuild.
                                // The version is bumped only when the node set actually changes
                                // (onNodesUpdated), which is the one case that requires re-resolve.
                                IOException ex = new IOException(
                                        "Node [" + response.getNode() + "] returned status " + statusCode);
                                if (previousException != null) {
                                    ex.addSuppressed(previousException);
                                }
                                if (cancellable.isCancelled()) {
                                    return Future.failedFuture(new CancellationException());
                                }
                                return sendViaResolver(request, ex, retriesLeft - 1, cancellable);
                            } else {
                                if (node != null) {
                                    markAlive(node);
                                }
                                return Future.failedFuture(new ResponseException(response));
                            }
                        },
                        failure -> {
                            IOException ex;
                            if (failure instanceof IOException) {
                                ex = (IOException) failure;
                            } else {
                                ex = new IOException("Request failed", failure);
                            }
                            if (previousException != null) {
                                ex.addSuppressed(previousException);
                            }
                            if (isNonRetryableException(failure) || cancellable.isCancelled()) {
                                return Future.failedFuture(
                                        cancellable.isCancelled() ? new CancellationException() : ex);
                            }
                            return sendViaResolver(request, ex, retriesLeft - 1, cancellable);
                        });
    }

    private NodeImpl findNodeByRemoteAddress(SocketAddress remote) {
        if (remote == null) {
            return null;
        }
        String remoteHost = remote.host();
        int remotePort = remote.port();
        for (NodeImpl node : this.routableNodes) {
            if (node.getHost().getHost().equals(remoteHost) && node.getResolvedPort() == remotePort) {
                return node;
            }
        }
        return null;
    }

    // --- Vert.x SPI implementations ---

    AddressResolver<ElasticsearchAddress> addressResolver() {
        return vertx -> new ElasticsearchEndpointResolver();
    }

    LoadBalancer loadBalancer() {
        return new DeadNodeAwareLoadBalancer(LoadBalancer.ROUND_ROBIN);
    }

    class ElasticsearchEndpointResolver
            implements EndpointResolver<ElasticsearchAddress, ElasticsearchServer, ResolverState, Object> {

        @Override
        public ElasticsearchAddress tryCast(io.vertx.core.net.Address address) {
            return address instanceof ElasticsearchAddress ? (ElasticsearchAddress) address : null;
        }

        @Override
        public SocketAddress addressOf(ElasticsearchServer server) {
            return server.address();
        }

        @Override
        public Future<ResolverState> resolve(ElasticsearchAddress address,
                EndpointBuilder<Object, ElasticsearchServer> builder) {
            // Node-selector filtering already happened when the node set was published, so we
            // register the pre-computed routable snapshot directly. Liveness (dead-node skipping)
            // is applied per selection round by DeadNodeAwareSelector, not here.
            List<NodeImpl> currentNodes = ResolverRequestDispatcher.this.routableNodes;

            for (NodeImpl node : currentNodes) {
                builder = builder.addServer(new ElasticsearchServer(node), node.getHost().toString());
            }

            Object endpoint = builder.build();
            int version = nodeListVersion.get();
            return Future.succeededFuture(new ResolverState(endpoint, version));
        }

        @Override
        public Object endpoint(ResolverState state) {
            return state.endpoint;
        }

        @Override
        public boolean isValid(ResolverState state) {
            return state.version == nodeListVersion.get();
        }

        @Override
        public void dispose(ResolverState data) {
        }

        @Override
        public void close() {
        }
    }

    static class ResolverState {
        final Object endpoint;
        final int version;

        ResolverState(Object endpoint, int version) {
            this.endpoint = endpoint;
            this.version = version;
        }
    }

    class DeadNodeAwareLoadBalancer implements LoadBalancer {

        private final LoadBalancer delegate;

        DeadNodeAwareLoadBalancer(LoadBalancer delegate) {
            this.delegate = delegate;
        }

        @Override
        public ServerSelector selector(List<? extends ServerEndpoint> servers) {
            ServerSelector baseSelector = delegate.selector(servers);
            return new DeadNodeAwareSelector(baseSelector, servers);
        }
    }

    class DeadNodeAwareSelector implements ServerSelector {

        private final ServerSelector base;
        private final List<? extends ServerEndpoint> servers;

        DeadNodeAwareSelector(ServerSelector base, List<? extends ServerEndpoint> servers) {
            this.base = base;
            this.servers = servers;
        }

        @Override
        public int select() {
            int size = servers.size();
            if (size == 0) {
                return -1;
            }
            for (int i = 0; i < size; i++) {
                int idx = base.select();
                if (idx < 0) {
                    return -1;
                }
                if (idx < size) {
                    ElasticsearchServer server = (ElasticsearchServer) servers.get(idx).unwrap();
                    if (server.node().shouldBeRetried()) {
                        return idx;
                    }
                }
            }
            // All dead -- pick least dead
            int leastDeadIdx = 0;
            long leastDeadUntil = Long.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                ElasticsearchServer server = (ElasticsearchServer) servers.get(i).unwrap();
                DeadHostState state = server.node().deadState.get();
                if (state != null && state.getDeadUntilNanos() < leastDeadUntil) {
                    leastDeadIdx = i;
                    leastDeadUntil = state.getDeadUntilNanos();
                }
            }
            return leastDeadIdx;
        }
    }

    private record NodeResponse(Response response, NodeImpl node) {
    }
}
