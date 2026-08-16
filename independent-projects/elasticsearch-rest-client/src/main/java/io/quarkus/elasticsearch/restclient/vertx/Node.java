package io.quarkus.elasticsearch.restclient.vertx;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an Elasticsearch node with a host URI and optional metadata such as
 * name, version, roles, and custom attributes.
 */
public interface Node {

    URI getHost();

    Set<URI> getBoundHosts();

    String getName();

    String getVersion();

    Roles getRoles();

    Map<String, List<String>> getAttributes();
}
