package io.quarkus.elasticsearch.restclient.vertx.discovery;

import java.util.List;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.vertx.core.Future;

/**
 * Interface for discovering available Elasticsearch nodes. Implementations query
 * the cluster and return the current list of nodes asynchronously.
 * <p>
 * <strong>Contract:</strong> the returned list must not contain duplicate hosts -- the
 * client does not deduplicate node updates (node discovery is the single writer of the
 * node list). The standard {@link ElasticsearchNodeDiscovery} satisfies this by
 * construction; custom implementations are responsible for returning a deduplicated
 * list. A {@code null} or empty result leaves the current node list unchanged.
 */
public interface NodeDiscovery {

    Future<List<Node>> discover();
}
