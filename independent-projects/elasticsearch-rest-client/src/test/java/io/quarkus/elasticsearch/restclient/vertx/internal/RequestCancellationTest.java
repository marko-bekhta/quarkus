package io.quarkus.elasticsearch.restclient.vertx.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkus.elasticsearch.restclient.vertx.CancellableFuture;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Request;
import io.quarkus.elasticsearch.restclient.vertx.Response;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;

class RequestCancellationTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void close() throws Exception {
        await(vertx.close());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancellationWhileAcquiringDoesNotSendOrRetry(boolean resolver) throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = await(vertx.createHttpServer().requestHandler(request -> {
            received.incrementAndGet();
            request.response().setStatusCode(503).end();
        }).listen(0));
        AbstractRequestDispatcher dispatcher = dispatcher(resolver, server);
        AcquisitionGate gate = new AcquisitionGate(dispatcher);

        // Start and cancel from outside Vert.x, while the HTTP acquisition is pending.
        CancellableFuture<Response> response = dispatcher.dispatch(new Request("POST", "/write"));
        Context owner = gate.acquiring.get(5, TimeUnit.SECONDS);
        assertThat(response.cancel()).isTrue();
        assertThat(response.cancel()).isFalse();
        assertCancelled(response);

        // Cancellation must complete without waiting for acquisition. A late request is reset.
        gate.release.complete();
        assertThat(gate.reset.get(5, TimeUnit.SECONDS)).isSameAs(owner);
        CompletableFuture<Void> drained = new CompletableFuture<>();
        owner.runOnContext(ignored -> drained.complete(null));
        drained.get(5, TimeUnit.SECONDS);
        assertThat(gate.sends.get()).isZero();
        assertThat(gate.attempts.get()).isEqualTo(1);
        assertThat(received.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancellationOnOwningContextDoesNotSend(boolean resolver) throws Exception {
        HttpServer server = await(vertx.createHttpServer().requestHandler(request -> request.response().end()).listen(0));
        AbstractRequestDispatcher dispatcher = dispatcher(resolver, server);
        AcquisitionGate gate = new AcquisitionGate(dispatcher);
        Context owner = vertx.getOrCreateContext();
        CompletableFuture<CancellableFuture<Response>> started = new CompletableFuture<>();
        owner.runOnContext(ignored -> started.complete(dispatcher.dispatch(new Request("GET", "/"))));
        CancellableFuture<Response> response = started.get(5, TimeUnit.SECONDS);
        assertThat(gate.acquiring.get(5, TimeUnit.SECONDS)).isSameAs(owner);
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        owner.runOnContext(ignored -> cancelled.complete(response.cancel()));
        assertThat(cancelled.get(5, TimeUnit.SECONDS)).isTrue();
        assertCancelled(response);
        gate.release.complete();
        assertThat(gate.reset.get(5, TimeUnit.SECONDS)).isSameAs(owner);
        assertThat(gate.sends.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancellationAbortsInFlightRequestOnOwningContext(boolean resolver) throws Exception {
        CompletableFuture<HttpServerRequest> received = new CompletableFuture<>();
        HttpServer server = await(vertx.createHttpServer().requestHandler(received::complete).listen(0));
        AbstractRequestDispatcher dispatcher = dispatcher(resolver, server);
        AcquisitionGate gate = new AcquisitionGate(dispatcher);
        gate.release.complete();
        CancellableFuture<Response> response = dispatcher.dispatch(new Request("POST", "/write"));
        Context owner = gate.acquiring.get(5, TimeUnit.SECONDS);
        HttpServerRequest request = received.get(5, TimeUnit.SECONDS);
        assertThat(response.cancel()).isTrue();
        assertCancelled(response);
        assertThat(gate.reset.get(5, TimeUnit.SECONDS)).isSameAs(owner);
        request.response().setStatusCode(503).end();
        assertThat(gate.sends.get()).isEqualTo(1);
        assertThat(gate.attempts.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancellationDuringRetryAcquisitionDoesNotSendAgain(boolean resolver) throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = await(vertx.createHttpServer().requestHandler(request -> {
            received.incrementAndGet();
            request.response().setStatusCode(503).end();
        }).listen(0));
        AbstractRequestDispatcher dispatcher = dispatcher(resolver, server);
        AcquisitionGate gate = new AcquisitionGate(dispatcher, 2);
        CancellableFuture<Response> response = dispatcher.dispatch(new Request("POST", "/write"));
        Context owner = gate.acquiring.get(5, TimeUnit.SECONDS);
        assertThat(gate.sends.get()).isEqualTo(1);
        assertThat(response.cancel()).isTrue();
        assertCancelled(response);
        // The previous attempt can also be reset; observe specifically the late retry request.
        gate.release.complete();
        assertThat(gate.blockedReset.get(5, TimeUnit.SECONDS)).isSameAs(owner);
        assertThat(gate.sends.get()).isEqualTo(1);
        assertThat(gate.attempts.get()).isEqualTo(2);
        assertThat(received.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void completedRequestCannotBeCancelled(boolean resolver) throws Exception {
        HttpServer server = await(vertx.createHttpServer().requestHandler(request -> request.response().end("ok")).listen(0));
        AbstractRequestDispatcher dispatcher = dispatcher(resolver, server);
        CancellableFuture<Response> response = dispatcher.dispatch(new Request("GET", "/"));
        assertThat(await(response).getStatusCode()).isEqualTo(200);
        assertThat(response.cancel()).isFalse();
        assertThat(response.isCancelled()).isFalse();
    }

    private AbstractRequestDispatcher dispatcher(boolean resolver, HttpServer server) {
        AbstractRequestDispatcher dispatcher = resolver
                ? RequestDispatchers.resolverDispatcher(NodeSelector.any(), FailureListener.NO_OP, Map.of(), null,
                        false, WarningsHandler.PERMISSIVE, vertx)
                : RequestDispatchers.defaultDispatcher(NodeSelector.any(), FailureListener.NO_OP, Map.of(), null,
                        false, WarningsHandler.PERMISSIVE, vertx);
        // Leave another candidate available so an unwanted retry would be observable.
        dispatcher.setNodes(List.of(new NodeImpl(URI.create("http://localhost:" + server.actualPort())),
                new NodeImpl(URI.create("http://127.0.0.1:" + server.actualPort()))));
        return dispatcher;
    }

    private static void assertCancelled(CancellableFuture<Response> response) {
        assertThatThrownBy(() -> await(response)).hasCauseInstanceOf(CancellationException.class);
        assertThat(response.isCancelled()).isTrue();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /**
     * Hold an actual HTTP acquisition before delivering it to the dispatcher. Keeping the
     * original Vert.x future preserves its context-affine callbacks, even when the test
     * releases the gate from another thread.
     */
    private static class AcquisitionGate {
        final Promise<Void> release = Promise.promise();
        final CompletableFuture<Context> acquiring = new CompletableFuture<>();
        final CompletableFuture<Context> reset = new CompletableFuture<>();
        final CompletableFuture<Context> blockedReset = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger sends = new AtomicInteger();

        AcquisitionGate(AbstractRequestDispatcher dispatcher) {
            this(dispatcher, 1);
        }

        AcquisitionGate(AbstractRequestDispatcher dispatcher, int blockedAttempt) {
            HttpClient original = dispatcher.httpClient;
            dispatcher.httpClient = (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[] { HttpClient.class }, (proxy, method, args) -> {
                        if (method.getName().equals("request") && args.length == 1
                                && args[0] instanceof RequestOptions options) {
                            int attempt = attempts.incrementAndGet();
                            return original.request(options).compose(request -> {
                                if (attempt != blockedAttempt) {
                                    return Future.succeededFuture(wrap(request, false));
                                }
                                acquiring.complete(Vertx.currentContext());
                                return release.future().map(ignored -> wrap(request, true));
                            });
                        }
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private HttpClientRequest wrap(HttpClientRequest request, boolean blocked) {
            return (HttpClientRequest) Proxy.newProxyInstance(HttpClientRequest.class.getClassLoader(),
                    new Class<?>[] { HttpClientRequest.class }, (proxy, method, args) -> {
                        if (method.getName().equals("send")) {
                            sends.incrementAndGet();
                        } else if (method.getName().equals("reset")) {
                            reset.complete(Vertx.currentContext());
                            if (blocked) {
                                blockedReset.complete(Vertx.currentContext());
                            }
                        }
                        try {
                            return method.invoke(request, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
