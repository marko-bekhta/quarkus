package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.quarkus.elasticsearch.restclient.vertx.BackoffStrategy;
import io.quarkus.elasticsearch.restclient.vertx.FailureListener;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.WarningsHandler;
import io.vertx.core.Vertx;

final class RequestDispatchers {

    private RequestDispatchers() {
    }

    static DefaultRequestDispatcher defaultDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler) {
        return defaultDispatcher(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, null);
    }

    static DefaultRequestDispatcher defaultDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler, Vertx vertx) {
        return new DefaultRequestDispatcher(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, List.of(), null, null,
                HttpConstants.Scheme.HTTP, vertx, null, null, null, System::nanoTime);
    }

    static DefaultRequestDispatcher defaultDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler,
            Supplier<Long> nanoTimeSupplier, BackoffStrategy backoffStrategy) {
        return new DefaultRequestDispatcher(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, List.of(), null, null,
                HttpConstants.Scheme.HTTP, null, null, null, backoffStrategy, nanoTimeSupplier);
    }

    static ResolverRequestDispatcher resolverDispatcher(NodeSelector nodeSelector, FailureListener failureListener,
            Map<String, String> defaultHeaders, String pathPrefix,
            boolean compressionEnabled, WarningsHandler defaultWarningsHandler, Vertx vertx) {
        return new ResolverRequestDispatcher(nodeSelector, failureListener, defaultHeaders, pathPrefix,
                compressionEnabled, defaultWarningsHandler, List.of(), null, null,
                HttpConstants.Scheme.HTTP, vertx, null, null, null);
    }
}
