package io.quarkus.elasticsearch.restclient.vertx;

import java.util.List;

/**
 * Determines whether Elasticsearch deprecation warnings present in a response should
 * cause the request to fail. Built-in implementations include {@link #PERMISSIVE}
 * (never fails) and {@link #STRICT} (fails on any warning).
 */
public interface WarningsHandler {

    WarningsHandler PERMISSIVE = warnings -> false;

    WarningsHandler STRICT = warnings -> warnings != null && !warnings.isEmpty();

    boolean shouldFail(List<String> warnings);
}
