package io.quarkus.elasticsearch.restclient.vertx;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vertx.core.AsyncResult;
import io.vertx.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;

/**
 * A {@link Future} that supports cancellation of in-flight HTTP requests and
 * prevention of retries. Returned by {@link VertxElasticsearchClient#performRequestAsync}.
 * <p>
 * Calling {@link #cancel()} aborts the current HTTP request (if any) and prevents
 * the dispatch chain from retrying on subsequent nodes.
 */
public final class CancellableFuture<T> implements Future<T> {

    private enum State {
        ACTIVE,
        CANCELLED,
        COMPLETED
    }

    private final Future<T> delegate;
    private final Promise<T> result = Promise.promise();
    private final Context context;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    // Accessed only on the operation's context, including when cancellation resets it.
    private HttpClientRequest currentRequest;

    public CancellableFuture(Future<T> operation, Context context) {
        this.context = context;
        this.delegate = result.future();
        operation.onComplete(outcome -> runOnContext(() -> {
            if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
                currentRequest = null;
                result.handle(outcome);
            }
        }));
    }

    /**
     * Requests cancellation on the context that owns the HTTP operation. If the operation
     * has already completed, this is a no-op and returns {@code false}.
     * <p>
     * A successful call reserves a cancelled outcome immediately. Resetting the current
     * HTTP request and completing this future with {@link CancellationException} happen
     * on the owning context, without waiting for connection acquisition to finish.
     * Sending already in progress may reach the server before cancellation is processed.
     *
     * @return {@code true} if this call newly accepted cancellation
     */
    public boolean cancel() {
        if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
            return false;
        }
        runOnContext(() -> {
            HttpClientRequest request = currentRequest;
            currentRequest = null;
            result.tryFail(new CancellationException());
            if (request != null) {
                request.reset();
            }
        });
        return true;
    }

    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    /**
     * Registers a newly acquired request on the owning context, immediately before sending.
     * A request acquired after cancellation is reset and must not be sent.
     *
     * @return whether the dispatcher may send the request
     */
    public boolean setCurrentRequest(HttpClientRequest request) {
        if (isCancelled()) {
            request.reset();
            return false;
        }
        currentRequest = request;
        return true;
    }

    private void runOnContext(Runnable action) {
        if (Vertx.currentContext() == context) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
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
