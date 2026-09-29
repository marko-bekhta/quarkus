package io.quarkus.elasticsearch.restclient.vertx;

import java.io.IOException;

/**
 * Wraps an Elasticsearch server error response, providing access to the full
 * {@link Response} for inspection. Retryable responses are reported after available nodes
 * have been exhausted.
 */
public class ResponseException extends IOException {

    private final Response response;

    public ResponseException(Response response) {
        super(buildMessage(response));
        this.response = response;
    }

    public Response getResponse() {
        return response;
    }

    private static String buildMessage(Response response) {
        StringBuilder sb = new StringBuilder("Elasticsearch request ")
                .append(response.getRequestMethod())
                .append(' ').append(response.getNode())
                .append(" failed with status ").append(response.getStatusCode());
        String statusMessage = response.getStatusMessage();
        if (statusMessage != null) {
            sb.append(' ').append(statusMessage);
        }
        if (response.getBody() != null && response.getBody().length() > 0) {
            sb.append("; response body:\n").append(response.getBody().toString());
        }
        return sb.toString();
    }
}
