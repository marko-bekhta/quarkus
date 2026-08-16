package io.quarkus.elasticsearch.restclient.vertx;

import java.util.Map;

/**
 * Factory for creating a {@link RequestDispatcher}. Implementations capture
 * dispatch strategy settings (node selector, headers, compression, etc.) and
 * receive runtime context at creation time via the package-private
 * {@code create} method.
 * <p>
 * Users obtain a factory from {@link RequestDispatcher#roundRobin()} or
 * {@link RequestDispatcher#vertxResolver()}, configure it with fluent setters,
 * and pass it to the client builder.
 */
public abstract class RequestDispatcherFactory {

    protected NodeSelector nodeSelector = NodeSelector.any();
    protected FailureListener failureListener = FailureListener.NO_OP;
    protected Map<String, String> defaultHeaders = Map.of();
    protected String pathPrefix;
    protected boolean compressionEnabled;
    protected WarningsHandler warningsHandler = WarningsHandler.PERMISSIVE;
    protected BackoffStrategy backoffStrategy = BackoffStrategy.DEFAULT;

    /**
     * Sets the {@link NodeSelector} used to filter candidate nodes before dispatch.
     *
     * @param nodeSelector the selector (may be {@code null} for {@link NodeSelector#any()})
     * @return this factory for chaining
     */
    public RequestDispatcherFactory nodeSelector(NodeSelector nodeSelector) {
        this.nodeSelector = nodeSelector;
        return this;
    }

    /**
     * Sets the {@link FailureListener} notified when a node is marked dead.
     *
     * @param failureListener the listener (may be {@code null} for {@link FailureListener#NO_OP})
     * @return this factory for chaining
     */
    public RequestDispatcherFactory failureListener(FailureListener failureListener) {
        this.failureListener = failureListener;
        return this;
    }

    /**
     * Sets default headers sent with every request.
     *
     * @param defaultHeaders the headers (may be {@code null} for no defaults)
     * @return this factory for chaining
     */
    public RequestDispatcherFactory defaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders != null ? Map.copyOf(defaultHeaders) : Map.of();
        return this;
    }

    /**
     * Sets a URI path prefix prepended to every request endpoint.
     *
     * @param pathPrefix the prefix (may be {@code null})
     * @return this factory for chaining
     */
    public RequestDispatcherFactory pathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
        return this;
    }

    /**
     * Enables or disables sending {@code Accept-Encoding: gzip} with every request.
     *
     * @param compressionEnabled whether to enable compression
     * @return this factory for chaining
     */
    public RequestDispatcherFactory compressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
        return this;
    }

    /**
     * Sets the {@link WarningsHandler} for Elasticsearch deprecation warnings.
     *
     * @param warningsHandler the handler (may be {@code null} for {@link WarningsHandler#PERMISSIVE})
     * @return this factory for chaining
     */
    public RequestDispatcherFactory warningsHandler(WarningsHandler warningsHandler) {
        this.warningsHandler = warningsHandler;
        return this;
    }

    /**
     * Sets the {@link BackoffStrategy} that determines how long a node stays dead after a
     * failure, as a function of consecutive failed attempts.
     *
     * @param backoffStrategy the strategy (may be {@code null} for {@link BackoffStrategy#DEFAULT})
     * @return this factory for chaining
     */
    public RequestDispatcherFactory backoffStrategy(BackoffStrategy backoffStrategy) {
        this.backoffStrategy = backoffStrategy != null ? backoffStrategy : BackoffStrategy.DEFAULT;
        return this;
    }

    /**
     * Creates a {@link RequestDispatcher} using the captured settings and the provided
     * runtime context. Called internally by the client builder.
     */
    abstract RequestDispatcher create(RequestDispatcherContext context);
}
