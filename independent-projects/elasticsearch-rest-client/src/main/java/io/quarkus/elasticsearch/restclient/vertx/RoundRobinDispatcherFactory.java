package io.quarkus.elasticsearch.restclient.vertx;

import io.quarkus.elasticsearch.restclient.vertx.internal.DefaultRequestDispatcher;

/**
 * Factory that creates a {@link DefaultRequestDispatcher} with round-robin
 * node selection, dead-node tracking, and exponential backoff.
 */
class RoundRobinDispatcherFactory extends RequestDispatcherFactory {

    @Override
    RequestDispatcher create(RequestDispatcherContext context) {
        return new DefaultRequestDispatcher(
                nodeSelector, failureListener, defaultHeaders,
                pathPrefix, compressionEnabled, warningsHandler,
                context.initialNodes(), context.nodeDiscoveryConfigurer(),
                context.client(), context.scheme(), context.vertx(),
                context.httpClientOptions(), context.poolOptions(), backoffStrategy);
    }
}
