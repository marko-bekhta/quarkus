package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

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
import io.vertx.core.Context;
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
                httpClientOptions, poolOptions, backoffStrategy, System::nanoTime);
        this.scheme = scheme;
        this.defaultWarningsHandler = defaultWarningsHandler != null ? defaultWarningsHandler : WarningsHandler.PERMISSIVE;
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
    public CancellableFuture<Response> dispatch(Request request) {
        return dispatch(request, ElasticsearchAddress.INSTANCE);
    }

    @Override
    CancellableFuture<Response> dispatchForDiscovery(Request request) {
        return dispatch(request, ElasticsearchAddress.DISCOVERY);
    }

    private List<NodeImpl> nodesFor(ElasticsearchAddress address) {
        return nodesFor(nodeSnapshot, address);
    }

    private List<NodeImpl> nodesFor(NodeSnapshot snapshot, ElasticsearchAddress address) {
        return address == ElasticsearchAddress.DISCOVERY ? snapshot.allNodes() : snapshot.routableNodes();
    }

    private CancellableFuture<Response> dispatch(Request request, ElasticsearchAddress address) {
        Promise<Response> promise = Promise.promise();
        Context context = vertx.getOrCreateContext();
        CancellableFuture<Response> cancellable = new CancellableFuture<>(promise.future(), context);

        // HttpClient captures the current context for acquisition, response and retry callbacks.
        context.runOnContext(ignored -> {
            NodeSnapshot snapshot = nodeSnapshot;
            List<NodeImpl> currentNodes = nodesFor(snapshot, address);
            if (currentNodes.isEmpty()) {
                promise.fail(noRoutableNodesException());
                return;
            }
            try {
                sendViaResolver(request, address, null, currentNodes.size(), cancellable).onComplete(promise);
            } catch (Exception e) {
                promise.tryFail(e);
            }
        });
        return cancellable;
    }

    private Future<Response> sendViaResolver(Request request, ElasticsearchAddress address, Throwable previousException,
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
                .setServer(address)
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
                    if (!cancellable.setCurrentRequest(httpRequest)) {
                        return Future.failedFuture(new CancellationException());
                    }
                    applyHeaders(httpRequest, request);
                    if (request.getBody() != null) {
                        return httpRequest.send(request.getBody());
                    }
                    return httpRequest.send();
                })
                .compose(httpResponse -> httpResponse.body().map(body -> {
                    SocketAddress remote = httpResponse.request().connection().remoteAddress();
                    NodeImpl node = findNodeByRemoteAddress(remote, address);
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
                                // No snapshot version bump here: the node set itself is unchanged,
                                // only this node's liveness. DeadNodeAwareSelector consults dead
                                // state live on every select(), so the cached endpoint stays valid
                                // and skips the dead node without a global resolver-cache rebuild.
                                // The version is bumped only when the node set actually changes
                                // (setNodes), which is the one case that requires re-resolve.
                                IOException ex = new IOException(
                                        "Node [" + response.getNode() + "] returned status " + statusCode);
                                if (previousException != null) {
                                    ex.addSuppressed(previousException);
                                }
                                if (cancellable.isCancelled()) {
                                    return Future.failedFuture(new CancellationException());
                                }
                                return sendViaResolver(request, address, ex, retriesLeft - 1, cancellable);
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
                            return sendViaResolver(request, address, ex, retriesLeft - 1, cancellable);
                        });
    }

    private NodeImpl findNodeByRemoteAddress(SocketAddress remote, ElasticsearchAddress address) {
        if (remote == null) {
            return null;
        }
        String remoteHost = remote.host();
        int remotePort = remote.port();
        for (NodeImpl node : nodesFor(address)) {
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
            // Application requests use the pre-computed routable snapshot; discovery uses
            // all known nodes with a separate address/cache entry. Liveness (dead-node skipping)
            // is applied per selection round by DeadNodeAwareSelector, not here.
            NodeSnapshot snapshot = nodeSnapshot;
            List<NodeImpl> currentNodes = nodesFor(snapshot, address);

            for (NodeImpl node : currentNodes) {
                builder = builder.addServer(new ElasticsearchServer(node), node.getHost().toString());
            }

            Object endpoint = builder.build();
            return Future.succeededFuture(new ResolverState(endpoint, snapshot.version()));
        }

        @Override
        public Object endpoint(ResolverState state) {
            return state.endpoint;
        }

        @Override
        public boolean isValid(ResolverState state) {
            return state.version == nodeSnapshot.version();
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
        final long version;

        ResolverState(Object endpoint, long version) {
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
