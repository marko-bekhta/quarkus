package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkus.elasticsearch.restclient.vertx.internal.NodeImpl;

class NodeTest {

    @Test
    void uriOnlyConstruction() {
        NodeImpl node = new NodeImpl(URI.create("http://localhost:9200"));
        assertThat(node.getHost()).isEqualTo(URI.create("http://localhost:9200"));
        assertThat(node.getBoundHosts()).isNull();
        assertThat(node.getName()).isNull();
        assertThat(node.getVersion()).isNull();
        assertThat(node.getRoles()).isEqualTo(Roles.NONE);
        assertThat(node.getAttributes()).isNull();
    }

    @Test
    void fullConstruction() {
        URI host = URI.create("http://es-1:9200");
        Set<URI> boundHosts = Set.of(URI.create("http://10.0.0.1:9200"));
        Roles roles = new Roles(Set.of("master", "data"));
        Map<String, List<String>> attrs = Map.of("zone", List.of("us-east-1a"));

        NodeImpl node = new NodeImpl(host, boundHosts, "node-1", "8.17.0", roles, attrs);

        assertThat(node.getHost()).isEqualTo(host);
        assertThat(node.getBoundHosts()).containsExactlyInAnyOrderElementsOf(boundHosts);
        assertThat(node.getName()).isEqualTo("node-1");
        assertThat(node.getVersion()).isEqualTo("8.17.0");
        assertThat(node.getRoles()).isEqualTo(roles);
        assertThat(node.getAttributes()).isEqualTo(attrs);
    }

    @Test
    void nullHostThrows() {
        assertThatThrownBy(() -> new NodeImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityBasedOnHostOnly() {
        URI host = URI.create("http://localhost:9200");
        Roles roles = new Roles(Set.of("data"));

        NodeImpl a = new NodeImpl(host, null, "n1", "8.17.0", roles, null);
        NodeImpl b = new NodeImpl(host, null, "n1", "8.17.0", roles, null);
        NodeImpl differentName = new NodeImpl(host, null, "n2", "8.17.0", roles, null);
        NodeImpl differentVersion = new NodeImpl(host, null, "n1", "9.0.0", roles, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isEqualTo(differentName);
        assertThat(a).isEqualTo(differentVersion);
    }

    @Test
    void differentPortNotEqual() {
        NodeImpl a = new NodeImpl(URI.create("http://localhost:9200"));
        NodeImpl b = new NodeImpl(URI.create("http://localhost:9201"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void defensiveCopyOfAttributes() {
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("zone", List.of("us-east-1a"));
        NodeImpl node = new NodeImpl(URI.create("http://localhost:9200"), null, null, null, null, attrs);
        assertThat(node.getAttributes()).containsKey("zone");
    }

    @Test
    void toStringContainsHost() {
        NodeImpl node = new NodeImpl(URI.create("http://es-1:9200"), null, "my-node", "8.17.0",
                new Roles(Set.of("data")), null);
        String str = node.toString();
        assertThat(str).contains("es-1:9200");
        assertThat(str).contains("my-node");
        assertThat(str).contains("8.17.0");
    }

    @Test
    void implementsNodeInterface() {
        Node node = new NodeImpl(URI.create("http://localhost:9200"));
        assertThat(node.getHost()).isEqualTo(URI.create("http://localhost:9200"));
    }

    @Test
    void fromReturnsExistingNodeImpl() {
        NodeImpl original = new NodeImpl(URI.create("http://localhost:9200"));
        NodeImpl converted = NodeImpl.from(original);
        assertThat(converted).isSameAs(original);
    }
}
