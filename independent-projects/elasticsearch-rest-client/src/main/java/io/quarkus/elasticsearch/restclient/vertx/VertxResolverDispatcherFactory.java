package io.quarkus.elasticsearch.restclient.vertx;

import io.quarkus.elasticsearch.restclient.vertx.internal.ResolverRequestDispatcher;

/**
 * Factory that creates a {@link ResolverRequestDispatcher} integrating with
 * the Vert.x {@code AddressResolver} and {@code LoadBalancer} SPIs.
 */
class VertxResolverDispatcherFactory extends RequestDispatcherFactory {

    @Override
    RequestDispatcher create(RequestDispatcherContext context) {
        return new ResolverRequestDispatcher(
                nodeSelector, failureListener, defaultHeaders,
                pathPrefix, compressionEnabled, warningsHandler,
                context.initialNodes(), context.nodeDiscoveryConfigurer(),
                context.client(), context.scheme(), context.vertx(),
                context.httpClientOptions(), context.poolOptions(), backoffStrategy);
    }
}
