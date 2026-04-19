package lt.satsyuk.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityEndpointGroupResolverTest {

    @Test
    void resolve_mapsKnownPathGroupsAndFallbacks() {
        assertThat(SecurityEndpointGroupResolver.resolve(null)).isEqualTo("unknown");
        assertThat(SecurityEndpointGroupResolver.resolve("   ")).isEqualTo("unknown");
        assertThat(SecurityEndpointGroupResolver.resolve("/api/auth/login")).isEqualTo("auth");
        assertThat(SecurityEndpointGroupResolver.resolve("/api/clients/1")).isEqualTo("clients");
        assertThat(SecurityEndpointGroupResolver.resolve("/api/requests/1")).isEqualTo("requests");
        assertThat(SecurityEndpointGroupResolver.resolve("/api/accounts/client/1")).isEqualTo("accounts");
        assertThat(SecurityEndpointGroupResolver.resolve("/actuator/prometheus")).isEqualTo("actuator");
        assertThat(SecurityEndpointGroupResolver.resolve("/swagger-ui/index.html")).isEqualTo("docs");
        assertThat(SecurityEndpointGroupResolver.resolve("/v3/api-docs")).isEqualTo("docs");
        assertThat(SecurityEndpointGroupResolver.resolve("/something/else")).isEqualTo("other");
    }
}
