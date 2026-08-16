package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;

class NodeSelectorTest {

    @Test
    void anyKeepsAllNodes() {
        List<NodeImpl> nodes = new ArrayList<>(List.of(
                new NodeImpl(URI.create("http://n1:9200")),
                new NodeImpl(URI.create("http://n2:9200")),
                new NodeImpl(URI.create("http://n3:9200"))));
        NodeSelector.any().select(nodes);
        assertThat(nodes).hasSize(3);
    }

    @Test
    void anyKeepsEmptyIterable() {
        List<NodeImpl> nodes = new ArrayList<>();
        NodeSelector.any().select(nodes);
        assertThat(nodes).isEmpty();
    }

    @Test
    void skipDedicatedMastersRemovesMasterOnly() {
        NodeImpl masterOnly = nodeWithRoles("http://n1:9200", Set.of("master"));
        NodeImpl dataNode = nodeWithRoles("http://n2:9200", Set.of("data"));
        NodeImpl ingestNode = nodeWithRoles("http://n3:9200", Set.of("ingest"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterOnly, dataNode, ingestNode));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(dataNode, ingestNode);
    }

    @Test
    void skipDedicatedMastersKeepsMasterWithData() {
        NodeImpl masterData = nodeWithRoles("http://n1:9200", Set.of("master", "data"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterData));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(masterData);
    }

    @Test
    void skipDedicatedMastersKeepsMasterWithDataHot() {
        NodeImpl masterDataHot = nodeWithRoles("http://n1:9200", Set.of("master", "data_hot"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterDataHot));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(masterDataHot);
    }

    @Test
    void skipDedicatedMastersKeepsMasterWithIngest() {
        NodeImpl masterIngest = nodeWithRoles("http://n1:9200", Set.of("master", "ingest"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterIngest));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(masterIngest);
    }

    @Test
    void skipDedicatedMastersKeepsNodesWithNoRoleMetadata() {
        NodeImpl noRoles = new NodeImpl(URI.create("http://n1:9200"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(noRoles));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(noRoles);
    }

    @Test
    void skipDedicatedMastersRemovesClusterManagerOnly() {
        NodeImpl clusterManagerOnly = nodeWithRoles("http://n1:9200", Set.of("cluster_manager"));
        NodeImpl dataNode = nodeWithRoles("http://n2:9200", Set.of("data"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(clusterManagerOnly, dataNode));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(dataNode);
    }

    @Test
    void skipDedicatedMastersKeepsClusterManagerWithData() {
        NodeImpl cmData = nodeWithRoles("http://n1:9200", Set.of("cluster_manager", "data"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(cmData));

        NodeSelector.skipDedicatedMasters().select(nodes);

        assertThat(nodes).containsExactly(cmData);
    }

    @Test
    void preferHasAttributeKeepsOnlyMatchingWhenSomeMatch() {
        NodeImpl zoneA = nodeWithAttributes("http://n1:9200", Map.of("zone", List.of("a")));
        NodeImpl zoneB = nodeWithAttributes("http://n2:9200", Map.of("zone", List.of("b")));
        NodeImpl alsoA = nodeWithAttributes("http://n3:9200", Map.of("zone", List.of("a")));
        List<NodeImpl> nodes = new ArrayList<>(List.of(zoneA, zoneB, alsoA));

        NodeSelector.preferHasAttribute("zone", "a").select(nodes);

        assertThat(nodes).containsExactly(zoneA, alsoA);
    }

    @Test
    void preferHasAttributeMatchesMultiValuedAttribute() {
        NodeImpl multi = nodeWithAttributes("http://n1:9200", Map.of("zone", List.of("a", "b")));
        NodeImpl other = nodeWithAttributes("http://n2:9200", Map.of("zone", List.of("c")));
        List<NodeImpl> nodes = new ArrayList<>(List.of(multi, other));

        NodeSelector.preferHasAttribute("zone", "b").select(nodes);

        assertThat(nodes).containsExactly(multi);
    }

    @Test
    void preferHasAttributeKeepsAllWhenNoneMatch() {
        NodeImpl zoneA = nodeWithAttributes("http://n1:9200", Map.of("zone", List.of("a")));
        NodeImpl zoneB = nodeWithAttributes("http://n2:9200", Map.of("zone", List.of("b")));
        List<NodeImpl> nodes = new ArrayList<>(List.of(zoneA, zoneB));

        NodeSelector.preferHasAttribute("zone", "c").select(nodes);

        assertThat(nodes).containsExactly(zoneA, zoneB);
    }

    @Test
    void preferHasAttributeTreatsUnknownAttributesAsNonMatching() {
        NodeImpl zoneA = nodeWithAttributes("http://n1:9200", Map.of("zone", List.of("a")));
        NodeImpl noAttributes = new NodeImpl(URI.create("http://n2:9200"));
        List<NodeImpl> nodes = new ArrayList<>(List.of(zoneA, noAttributes));

        NodeSelector.preferHasAttribute("zone", "a").select(nodes);

        assertThat(nodes).containsExactly(zoneA);
    }

    @Test
    void preferHasAttributeKeepsEmptyIterable() {
        List<NodeImpl> nodes = new ArrayList<>();
        NodeSelector.preferHasAttribute("zone", "a").select(nodes);
        assertThat(nodes).isEmpty();
    }

    @Test
    void andAppliesBothSelectorsInOrder() {
        NodeImpl masterOnlyZoneA = new NodeImpl(URI.create("http://n1:9200"), null, null, null,
                new Roles(Set.of("master")), Map.of("zone", List.of("a")));
        NodeImpl dataZoneA = new NodeImpl(URI.create("http://n2:9200"), null, null, null,
                new Roles(Set.of("data")), Map.of("zone", List.of("a")));
        NodeImpl dataZoneB = new NodeImpl(URI.create("http://n3:9200"), null, null, null,
                new Roles(Set.of("data")), Map.of("zone", List.of("b")));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterOnlyZoneA, dataZoneA, dataZoneB));

        NodeSelector.skipDedicatedMasters().and(NodeSelector.preferHasAttribute("zone", "a")).select(nodes);

        assertThat(nodes).containsExactly(dataZoneA);
    }

    @Test
    void andPreferenceIsEvaluatedAfterEarlierFiltering() {
        // The only zone=a node is a dedicated master; once it is dropped, no survivor has zone=a,
        // so preferHasAttribute falls back to keeping all remaining data nodes.
        NodeImpl masterOnlyZoneA = new NodeImpl(URI.create("http://n1:9200"), null, null, null,
                new Roles(Set.of("master")), Map.of("zone", List.of("a")));
        NodeImpl dataZoneB = new NodeImpl(URI.create("http://n2:9200"), null, null, null,
                new Roles(Set.of("data")), Map.of("zone", List.of("b")));
        NodeImpl dataZoneC = new NodeImpl(URI.create("http://n3:9200"), null, null, null,
                new Roles(Set.of("data")), Map.of("zone", List.of("c")));
        List<NodeImpl> nodes = new ArrayList<>(List.of(masterOnlyZoneA, dataZoneB, dataZoneC));

        NodeSelector.skipDedicatedMasters().and(NodeSelector.preferHasAttribute("zone", "a")).select(nodes);

        assertThat(nodes).containsExactly(dataZoneB, dataZoneC);
    }

    private static NodeImpl nodeWithRoles(String uri, Set<String> roles) {
        return new NodeImpl(URI.create(uri), null, null, null, new Roles(roles), null);
    }

    private static NodeImpl nodeWithAttributes(String uri, Map<String, List<String>> attributes) {
        return new NodeImpl(URI.create(uri), null, null, null, null, attributes);
    }
}
