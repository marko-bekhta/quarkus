package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.RequestDispatcher;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.ResponseException;
import io.quarkus.elasticsearch.restclient.vertx.VertxElasticsearchClient;
import io.quarkus.elasticsearch.restclient.vertx.WarningFailureException;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryConfigurer;
import io.quarkus.elasticsearch.restclient.vertx.discovery.NodeDiscoveryScheduler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;

/**
 * Base class for {@link RequestDispatcher} implementations, providing shared infrastructure
 * for node management, dead-node tracking, header application, and response handling.
 * Subclasses implement {@link #dispatch(Request)} to define their own node
 * selection and retry strategy.
 * <p>
 * The base class also owns the {@link HttpClient}: each subclass implements
 * {@link #createHttpClient(Vertx, HttpClientOptions, PoolOptions)} to build the flavor of
 * client it needs, and the base stores it in {@link #httpClient} and closes it in
 * {@link #close()}.
 */
abstract class AbstractRequestDispatcher implements RequestDispatcher {

    private static final Logger LOG = Logger.getLogger(AbstractRequestDispatcher.class);

    protected final NodeSelector nodeSelector;

    private final FailureListener failureListener;
    private final Map<String, String> defaultHeaders;
    private final String pathPrefix;
    private final boolean compressionEnabled;
    private final WarningsHandler defaultWarningsHandler;
    private final Supplier<Long> nanoTimeSupplier;
    private final BackoffStrategy backoffStrategy;

    private final NodeDiscoveryScheduler nodeDiscoveryScheduler;

    /**
     * Every node currently known to the client -- seeds plus whatever discovery last found.
     * This is the internal source of truth (see {@link #getNodes()}); it is never filtered.
     */
    protected volatile List<NodeImpl> allNodes;

    /**
     * The subset of {@link #allNodes} that this dispatcher will actually route traffic to,
     * i.e. the result of applying the {@link NodeSelector} (see {@link #computeRoutableNodes(List)}).
     * Recomputed only when the node set changes, so request-time routing reads a ready-made list.
     */
    protected volatile List<NodeImpl> routableNodes;

    /**
     * The HTTP client requests are sent through. Built once during construction by the
     * subclass's {@link #createHttpClient(Vertx, HttpClientOptions, PoolOptions)} and owned
     * by this base class, which closes it in {@link #close()}. {@code null} for the test-only
     * constructors that pass no {@link Vertx}.
     */
    protected HttpClient httpClient;

    // Test-only: convenience for unit tests that don't need initial nodes or node discovery.
    AbstractRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler) {
        this(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, System::nanoTime);
    }

    // Test-only: injects a deterministic clock for dead-node timing assertions.
    AbstractRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            Supplier<Long> nanoTimeSupplier) {
        this(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, nanoTimeSupplier, (BackoffStrategy) null);
    }

    // Test-only: injects a deterministic clock and backoff strategy for dead-node timing assertions.
    AbstractRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            Supplier<Long> nanoTimeSupplier, BackoffStrategy backoffStrategy) {
        this(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, nanoTimeSupplier, backoffStrategy,
                null, null, null, null, null, null, null);
    }

    AbstractRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            List<NodeImpl> initialNodes, NodeDiscoveryConfigurer nodeDiscoveryConfigurer,
            VertxElasticsearchClient client, HttpConstants.Scheme scheme,
            Vertx vertx, HttpClientOptions httpClientOptions, PoolOptions poolOptions,
            BackoffStrategy backoffStrategy) {
        this(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, System::nanoTime, backoffStrategy,
                initialNodes, nodeDiscoveryConfigurer, client, scheme, vertx,
                httpClientOptions, poolOptions);
    }

    private AbstractRequestDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            Supplier<Long> nanoTimeSupplier, BackoffStrategy backoffStrategy,
            List<NodeImpl> initialNodes, NodeDiscoveryConfigurer nodeDiscoveryConfigurer,
            VertxElasticsearchClient client, HttpConstants.Scheme scheme, Vertx vertx,
            HttpClientOptions httpClientOptions, PoolOptions poolOptions) {
        this.nodeSelector = nodeSelector != null ? nodeSelector : AnyNodeSelector.INSTANCE;
        this.failureListener = failureListener != null ? failureListener : FailureListener.NO_OP;
        this.defaultHeaders = defaultHeaders != null ? Map.copyOf(defaultHeaders) : Map.of();
        if (pathPrefix != null && (!pathPrefix.startsWith("/") || pathPrefix.endsWith("/"))) {
            throw new IllegalArgumentException(
                    "pathPrefix must start with '/' and must not end with '/', got: '" + pathPrefix + "'");
        }
        this.pathPrefix = pathPrefix;
        this.compressionEnabled = compressionEnabled;
        this.defaultWarningsHandler = defaultWarningsHandler != null ? defaultWarningsHandler : WarningsHandler.PERMISSIVE;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.backoffStrategy = backoffStrategy != null ? backoffStrategy : BackoffStrategy.DEFAULT;
        // Seed the node set through the same selection logic used by discovery updates.
        // We compute the routable subset inline rather than calling setNodes(): setNodes()
        // fires onNodesUpdated(), which subclasses override to touch state that is not yet
        // initialized while the superclass constructor runs.
        List<NodeImpl> initial = initialNodes != null ? List.copyOf(initialNodes) : List.of();
        this.allNodes = initial;
        this.routableNodes = computeRoutableNodes(initial);
        this.nodeDiscoveryScheduler = nodeDiscoveryConfigurer != null
                ? nodeDiscoveryConfigurer.createScheduler(client, vertx, this::setNodes, scheme)
                : null;
        // A node selector only ever sees described nodes, and only discovery produces them.
        // With discovery disabled the client stays on undescribed seed nodes forever, so a
        // non-default selector would silently never apply -- warn rather than mislead.
        if (this.nodeSelector != AnyNodeSelector.INSTANCE && this.nodeDiscoveryScheduler == null) {
            LOG.warnf("A non-default node selector [%s] is configured but node discovery is disabled; "
                    + "the selector will never apply because seed nodes carry no metadata to select on",
                    this.nodeSelector);
        }
        // Build the HTTP client last: createHttpClient() runs before subclass fields are
        // initialized, so its contract forbids reading instance state (it builds purely from
        // the arguments). The test-only constructors pass no Vertx and set the client, if any,
        // themselves.
        this.httpClient = vertx != null ? createHttpClient(vertx, httpClientOptions, poolOptions) : null;
    }

    @Override
    public abstract CancellableFuture<Response> dispatch(Request request);

    /**
     * Creates the {@link HttpClient} this dispatcher sends requests through. Called once from
     * the constructor; the returned client is owned by the base class, which stores it in
     * {@link #httpClient} and closes it in {@link #close()}.
     * <p>
     * <strong>Implementations must build the client purely from the supplied arguments and must
     * not read instance fields.</strong> This method runs during superclass construction, before
     * subclass fields are initialized, so any field access would observe {@code null}/default
     * values. Everything needed to construct the client is passed in.
     *
     * @param vertx the Vert.x instance to create the client from (never {@code null} here)
     * @param options caller-supplied HTTP client options, or {@code null} for defaults
     * @param poolOptions caller-supplied pool options, or {@code null} for defaults
     * @return the HTTP client to route requests through
     */
    protected abstract HttpClient createHttpClient(Vertx vertx, HttpClientOptions options, PoolOptions poolOptions);

    /**
     * Returns a copy of the given options with the transport-level defaults this client relies
     * on applied, for subclasses to use when building their {@link HttpClient}. Response
     * decompression is always enabled: it is harmless when no gzip response arrives and more
     * robust against proxies that compress unbidden. The {@code compressionEnabled} dispatch
     * flag only controls whether we advertise {@code Accept-Encoding: gzip} on outgoing
     * requests, not whether we can decode a compressed response.
     *
     * @param options caller-supplied options, or {@code null} to start from defaults
     * @return a fresh {@link HttpClientOptions} the caller may further mutate
     */
    protected static HttpClientOptions withTransportDefaults(HttpClientOptions options) {
        HttpClientOptions opts = options != null ? new HttpClientOptions(options) : new HttpClientOptions();
        opts.setDecompressionSupported(true);
        return opts;
    }

    /**
     * Replaces the entire node set, typically from a node-discovery round. Discovery is a
     * single writer that publishes the whole list at once, so this swaps both the raw and the
     * routable snapshots wholesale rather than diffing.
     * <p>
     * The incoming nodes are marked alive (a freshly discovered node has no reason to be
     * considered dead), the routable subset is recomputed via {@link #computeRoutableNodes(List)},
     * and {@link #onNodesUpdated()} notifies subclasses. The two snapshots are {@code volatile},
     * so concurrent readers in {@link #dispatch(Request)} always observe a fully
     * built list -- never a half-updated one.
     */
    void setNodes(List<NodeImpl> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        synchronized (this) {
            List<NodeImpl> snapshot = List.copyOf(nodes);
            for (NodeImpl node : snapshot) {
                node.markAlive();
            }
            this.allNodes = snapshot;
            this.routableNodes = computeRoutableNodes(snapshot);
            onNodesUpdated();
        }
    }

    /**
     * Applies the {@link NodeSelector} to a freshly received node list and returns the subset
     * this dispatcher will route to. This is the single point where selection happens.
     * <p>
     * Selection is a routing policy over node <em>metadata</em> (roles, attributes, version).
     * That metadata is fixed for the lifetime of a node object and only changes when discovery
     * publishes a new list, so the selector's verdict for a given node is constant between
     * updates. Computing the routable set once here -- instead of re-filtering on every request --
     * therefore yields the same result while doing the work only when it can actually change.
     * <p>
     * Undescribed seed nodes are always kept (see {@link NodeImpl#isDescribed()}): a
     * metadata-based selector cannot judge a bare seed URI, and dropping seeds would stop
     * discovery from ever running. Only described nodes are handed to the selector.
     * <p>
     * If selection leaves nothing routable while nodes were in fact configured, a warning is
     * logged; requests will then fail fast (see {@link #noRoutableNodesException()}) until
     * discovery supplies eligible nodes.
     */
    private List<NodeImpl> computeRoutableNodes(List<NodeImpl> incoming) {
        List<NodeImpl> described = new ArrayList<>(incoming.size());
        List<NodeImpl> routable = new ArrayList<>(incoming.size());
        for (NodeImpl node : incoming) {
            if (node.isDescribed()) {
                described.add(node);
            } else {
                routable.add(node);
            }
        }
        // NodeSelector.select() filters the described list in place.
        nodeSelector.select(described);
        routable.addAll(described);
        if (routable.isEmpty() && !incoming.isEmpty()) {
            LOG.warnf("Node selector [%s] filtered out all %d configured node(s); "
                    + "requests will fail until node discovery finds eligible nodes",
                    nodeSelector, incoming.size());
        }
        return List.copyOf(routable);
    }

    /**
     * Builds the exception thrown when there is nothing to route to, distinguishing the two
     * causes: no nodes were ever configured, versus the {@link NodeSelector} filtered them all
     * out. Shared by both dispatchers so the message stays consistent.
     */
    protected IOException noRoutableNodesException() {
        if (allNodes.isEmpty()) {
            return new IOException("No nodes are configured");
        }
        return new IOException("No routable nodes available: the node selector [" + nodeSelector
                + "] filtered out all " + allNodes.size() + " configured node(s)");
    }

    /**
     * Hook called inside the synchronized block of {@link #setNodes(List)} after nodes
     * have been updated. Subclasses may override to perform additional bookkeeping.
     */
    protected void onNodesUpdated() {
        // no-op by default
    }

    // Package-private accessor so unit tests can inspect the stored node set.
    // Not part of any public contract -- the node list is internal routing state.
    List<? extends Node> getNodes() {
        return allNodes;
    }

    @Override
    public Future<Void> close() {
        // Stop discovery first, then close the HTTP client. Both teardowns are asynchronous and
        // run on the event loop, so we compose their futures rather than blocking -- a blocking
        // join here would deadlock if close() were ever called from an event-loop thread.
        Future<Void> schedulerClosed = nodeDiscoveryScheduler != null
                ? nodeDiscoveryScheduler.close()
                : Future.succeededFuture();
        if (httpClient == null) {
            return schedulerClosed;
        }
        // eventually() runs the HTTP client close regardless of how the scheduler close resolved.
        return schedulerClosed.eventually(() -> httpClient.close());
    }

    protected void markDead(NodeImpl node) {
        node.markDead(nanoTimeSupplier, backoffStrategy);
        failureListener.onFailure(node);
        if (nodeDiscoveryScheduler != null) {
            nodeDiscoveryScheduler.discoverOnFailure();
        }
    }

    protected void markAlive(NodeImpl node) {
        node.markAlive();
    }

    /**
     * Returns the current time in nanoseconds from the dispatcher's clock. Uses the
     * injected time supplier (rather than {@link System#nanoTime()} directly) so that
     * dead-node deadlines and the liveness checks that read them share one clock and
     * remain consistent and testable.
     */
    protected long nowNanos() {
        return nanoTimeSupplier.get();
    }

    protected String buildUri(Request request) {
        if (pathPrefix != null) {
            return pathPrefix + request.getEndpoint() + request.getQueryString();
        }
        return request.getEndpoint() + request.getQueryString();
    }

    /**
     * Applies default headers, per-request headers, and compression to the given HTTP request.
     */
    protected void applyHeaders(HttpClientRequest httpRequest, Request request) {
        for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
            httpRequest.putHeader(header.getKey(), header.getValue());
        }
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            httpRequest.putHeader(header.getKey(), header.getValue());
        }
        if (compressionEnabled) {
            httpRequest.putHeader(HttpConstants.Headers.ACCEPT_ENCODING, HttpConstants.Headers.GZIP);
        }
    }

    /**
     * Sends a request to a single node, processes the response, and handles retry logic.
     * On success (status &lt; 500), the node is marked alive and warnings are checked.
     * On retryable errors (502, 503, 504), the node is marked dead and the {@code retryAction}
     * is invoked. On non-retryable server errors, a {@link ResponseException} is raised.
     * Connection failures also trigger the retry action.
     *
     * @param request the request to send
     * @param node the target node
     * @param previousException any exception from a prior attempt (for suppressed chaining)
     * @param retryAction function that receives the accumulated exception and returns the next retry future
     * @return the response future
     */
    protected Future<Response> sendToNode(Request request, NodeImpl node,
            Throwable previousException, Function<Throwable, Future<Response>> retryAction,
            CancellableFuture<Response> cancellable) {
        if (cancellable.isCancelled()) {
            return Future.failedFuture(new CancellationException());
        }
        RequestOptions options = node.newRequestOptions(request.getMethod(), buildUri(request));

        return httpClient.request(options)
                .compose(httpRequest -> {
                    cancellable.setCurrentRequest(httpRequest);
                    applyHeaders(httpRequest, request);
                    if (request.getBody() != null) {
                        return httpRequest.send(request.getBody());
                    } else {
                        return httpRequest.send();
                    }
                })
                .compose(httpResponse -> httpResponse.body().map(body -> new Response(
                        httpResponse.statusCode(),
                        httpResponse.statusMessage(),
                        httpResponse.headers(),
                        body,
                        node.getHost(),
                        request.getMethod())))
                .compose(response -> {
                    if (cancellable.isCancelled()) {
                        // The request was cancelled after the response arrived: do not
                        // deliver it (nor a ResponseException), and do not retry.
                        return Future.failedFuture(new CancellationException());
                    }
                    int statusCode = response.getStatusCode();
                    if (statusCode < 500) {
                        markAlive(node);
                        WarningsHandler handler = request.getWarningsHandler() != null
                                ? request.getWarningsHandler()
                                : defaultWarningsHandler;
                        if (handler != WarningsHandler.PERMISSIVE
                                && handler.shouldFail(response.getWarnings())) {
                            return Future.failedFuture(new WarningFailureException(response));
                        }
                        return Future.succeededFuture(response);
                    } else if (HttpConstants.isRetryableStatus(statusCode)) {
                        markDead(node);
                        IOException ex = new IOException(
                                "Node [" + node.getHost() + "] returned status " + statusCode);
                        if (previousException != null) {
                            ex.addSuppressed(previousException);
                        }
                        if (cancellable.isCancelled()) {
                            return Future.failedFuture(new CancellationException());
                        }
                        return retryAction.apply(ex);
                    } else {
                        markAlive(node);
                        return Future.failedFuture(new ResponseException(response));
                    }
                }, failure -> {
                    markDead(node);
                    IOException ex;
                    if (failure instanceof IOException) {
                        ex = (IOException) failure;
                    } else {
                        ex = new IOException("Request to [" + node.getHost() + "] failed", failure);
                    }
                    if (previousException != null) {
                        ex.addSuppressed(previousException);
                    }
                    if (isNonRetryableException(failure) || cancellable.isCancelled()) {
                        return Future.failedFuture(
                                cancellable.isCancelled() ? new CancellationException() : ex);
                    }
                    return retryAction.apply(ex);
                });
    }

    protected static boolean isNonRetryableException(Throwable e) {
        // Netty's TooLongFrameException means the response exceeded the buffer
        // limit -- retrying on another node would hit the same problem
        return e instanceof io.netty.handler.codec.TooLongFrameException;
    }

}
