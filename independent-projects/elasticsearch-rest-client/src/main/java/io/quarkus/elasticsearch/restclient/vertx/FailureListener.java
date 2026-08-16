package io.quarkus.elasticsearch.restclient.vertx;

/**
 * Callback interface invoked when a request to an Elasticsearch node fails. Can be
 * used to trigger actions such as node re-discovery on failure.
 */
public interface FailureListener {

    FailureListener NO_OP = node -> {
    };

    void onFailure(Node node);
}
