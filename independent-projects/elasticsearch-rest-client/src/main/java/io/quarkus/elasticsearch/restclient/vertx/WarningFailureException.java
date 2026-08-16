package io.quarkus.elasticsearch.restclient.vertx;

/**
 * Thrown when a {@link WarningsHandler} decides that the deprecation warnings in an
 * Elasticsearch response should cause the request to fail.
 */
public class WarningFailureException extends RuntimeException {

    private final Response response;

    public WarningFailureException(Response response) {
        super("Warnings handler flagged response with warnings: " + response.getWarnings());
        this.response = response;
    }

    public Response getResponse() {
        return response;
    }
}
