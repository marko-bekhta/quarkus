package io.quarkus.elasticsearch.restclient.vertx.internal;

import java.util.Iterator;

import io.quarkus.elasticsearch.restclient.vertx.Node;
import io.quarkus.elasticsearch.restclient.vertx.NodeSelector;
import io.quarkus.elasticsearch.restclient.vertx.Roles;

/**
 * {@link NodeSelector} that removes nodes which can only act as masters, keeping requests
 * off dedicated master-eligible nodes. Obtained via {@link NodeSelector#skipDedicatedMasters()}.
 * <p>
 * A node is removed only when it is master-eligible AND cannot contain data AND is not an
 * ingest node. A node that carries any data or ingest role is kept, as is any node whose role
 * metadata is unknown -- the selector never removes a node it cannot evaluate.
 * <p>
 * A stateless singleton; see {@link #INSTANCE}.
 */
public final class SkipDedicatedMastersNodeSelector implements NodeSelector {

    public static final SkipDedicatedMastersNodeSelector INSTANCE = new SkipDedicatedMastersNodeSelector();

    private static final String MASTER = "master";
    private static final String CLUSTER_MANAGER = "cluster_manager";
    private static final String DATA = "data";
    private static final String DATA_TIER_PREFIX = "data_";
    private static final String INGEST = "ingest";

    private SkipDedicatedMastersNodeSelector() {
    }

    @Override
    public void select(Iterable<? extends Node> nodes) {
        Iterator<? extends Node> it = nodes.iterator();
        while (it.hasNext()) {
            Roles roles = it.next().getRoles();
            if (isDedicatedMaster(roles)) {
                it.remove();
            }
        }
    }

    private static boolean isDedicatedMaster(Roles roles) {
        // Elasticsearch uses "master"; OpenSearch renamed it to "cluster_manager" (with "master"
        // kept as a deprecated alias), so either name counts as master-eligible.
        return roles.hasAny(MASTER, CLUSTER_MANAGER) && !canContainData(roles) && !roles.has(INGEST);
    }

    private static boolean canContainData(Roles roles) {
        // "data" holds every tier; the "data_*" tiers (data_hot, data_warm, … in Elasticsearch)
        // each hold data on their own, so any of them means the node can contain data.
        return roles.has(DATA) || roles.anyMatch(role -> role.startsWith(DATA_TIER_PREFIX));
    }

    @Override
    public String toString() {
        return "NodeSelector.skipDedicatedMasters()";
    }
}
