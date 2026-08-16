package io.quarkus.elasticsearch.restclient.vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

class RolesTest {

    @Test
    void hasReturnsTrueForPresentRole() {
        Roles roles = new Roles(Set.of("master", "data"));
        assertThat(roles.has("master")).isTrue();
        assertThat(roles.has("data")).isTrue();
    }

    @Test
    void hasReturnsFalseForAbsentRole() {
        Roles roles = new Roles(Set.of("data", "ingest"));
        assertThat(roles.has("master")).isFalse();
    }

    @Test
    void hasAnyReturnsTrueWhenAtLeastOnePresent() {
        Roles roles = new Roles(Set.of("cluster_manager", "data"));
        assertThat(roles.hasAny("master", "cluster_manager")).isTrue();
    }

    @Test
    void hasAnyReturnsFalseWhenNonePresent() {
        Roles roles = new Roles(Set.of("data", "ingest"));
        assertThat(roles.hasAny("master", "cluster_manager")).isFalse();
    }

    @Test
    void anyMatchMatchesRoleFamilyByPrefix() {
        Roles roles = new Roles(Set.of("data_hot", "data_warm", "data_cold", "data_frozen"));
        assertThat(roles.anyMatch(role -> role.startsWith("data_"))).isTrue();
    }

    @Test
    void anyMatchReturnsFalseWhenNothingMatches() {
        Roles roles = new Roles(Set.of("master", "ingest"));
        assertThat(roles.anyMatch(role -> role.startsWith("data_"))).isFalse();
    }

    @Test
    void emptyRolesMatchNothing() {
        Roles roles = new Roles(Set.of());
        assertThat(roles.has("master")).isFalse();
        assertThat(roles.hasAny("master", "data")).isFalse();
        assertThat(roles.anyMatch(role -> true)).isFalse();
    }

    @Test
    void rolesArePreservedAndDefensivelyCopied() {
        Roles roles = new Roles(Set.of("ml", "transform", "voting_only"));
        assertThat(roles.roles()).containsExactlyInAnyOrder("ml", "transform", "voting_only");
    }

    @Test
    void nullRolesThrows() {
        assertThatThrownBy(() -> new Roles(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equality() {
        Roles a = new Roles(Set.of("master", "data"));
        Roles b = new Roles(Set.of("data", "master"));
        Roles c = new Roles(Set.of("master"));
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }
}
