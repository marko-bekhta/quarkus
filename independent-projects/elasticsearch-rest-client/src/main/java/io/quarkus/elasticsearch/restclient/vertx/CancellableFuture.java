package io.quarkus.elasticsearch.restclient.vertx;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vertx.core.AsyncResult;
import io.vertx.core.Completable;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClientRequest;

/**
 * A {@link Future} that supports cancellation of in-flight HTTP requests and
 * prevention of retries. Returned by {@link VertxElasticsearchClient#performRequestAsync}.
 * <p>
 * Calling {@link #cancel()} aborts the current HTTP request (if any) and prevents
 * the dispatch chain from retrying on subsequent nodes.
 */
public final class CancellableFuture<T> implements Future<T> {

    private final Future<T> delegate;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpClientRequest> currentRequest = new AtomicReference<>();

    public CancellableFuture(Future<T> delegate) {
        this.delegate = delegate;
    }

    /**
     * Cancels the in-flight request and prevents retries. If the request has already
     * completed, this is a no-op and returns {@code false}.
     *
     * @return {@code true} if cancellation was newly set by this call
     */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        HttpClientRequest req = currentRequest.get();
        if (req != null) {
            req.reset();
        }
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setCurrentRequest(HttpClientRequest request) {
        currentRequest.set(request);
    }

    // --- Future<T> delegation ---

    @Override
    public boolean isComplete() {
        return delegate.isComplete();
    }

    @Override
    public T result() {
        return delegate.result();
    }

    @Override
    public Throwable cause() {
        return delegate.cause();
    }

    @Override
    public boolean succeeded() {
        return delegate.succeeded();
    }

    @Override
    public boolean failed() {
        return delegate.failed();
    }

    @Override
    public Future<T> onComplete(Completable<? super T> handler) {
        delegate.onComplete(handler);
        return this;
    }

    @Override
    public <U> Future<U> compose(Function<? super T, Future<U>> successMapper,
            Function<Throwable, Future<U>> failureMapper) {
        return delegate.compose(successMapper, failureMapper);
    }

    @Override
    public <U> Future<U> transform(Function<AsyncResult<T>, Future<U>> mapper) {
        return delegate.transform(mapper);
    }

    @Override
    public <U> Future<T> eventually(Supplier<Future<U>> mapper) {
        return delegate.eventually(mapper);
    }

    @Override
    public <U> Future<U> map(Function<? super T, U> mapper) {
        return delegate.map(mapper);
    }

    @Override
    public <V> Future<V> map(V value) {
        return delegate.map(value);
    }

    @Override
    public Future<T> otherwise(Function<Throwable, T> mapper) {
        return delegate.otherwise(mapper);
    }

    @Override
    public Future<T> otherwise(T value) {
        return delegate.otherwise(value);
    }

    @Override
    public Future<T> expecting(Expectation<? super T> expectation) {
        return delegate.expecting(expectation);
    }

    @Override
    public Future<T> timeout(long delay, TimeUnit unit) {
        return delegate.timeout(delay, unit);
    }
}
