package io.quarkus.elasticsearch.restclient.vertx.discovery;

/**
 * Thrown when node discovery fails to obtain or parse the cluster's node list. It is
 * surfaced as the cause of the failed {@link io.vertx.core.Future} returned by
 * {@link NodeDiscovery#discover()}.
 */
public class NodeDiscoveryException extends RuntimeException {

    public NodeDiscoveryException(String message) {
        super(message);
    }

    public NodeDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
