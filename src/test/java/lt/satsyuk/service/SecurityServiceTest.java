package lt.satsyuk.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityServiceTest {

    private final SecurityService securityService = new SecurityService();

    @Test
    void clientId_usesAzpFromBearerTokenAttributes() {
        BearerTokenAuthentication auth = bearerAuthentication(Map.of("azp", "mobile-app", "client_id", "fallback"));

        String clientId = securityService.clientId()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(clientId).isEqualTo("mobile-app");
    }

    @Test
    void clientId_usesClientIdWhenAzpMissing() {
        BearerTokenAuthentication auth = bearerAuthentication(Map.of("client_id", "backend-service"));

        String clientId = securityService.clientId()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(clientId).isEqualTo("backend-service");
    }

    @Test
    void clientId_returnsAnonymousForNonBearerAndMissingContext() {
        Authentication authentication = new TestingAuthenticationToken("user", "pwd");

        String fromUnsupportedAuth = securityService.clientId(authentication);
        String fromMissingContext = securityService.clientId().block();

        assertThat(fromUnsupportedAuth).isEqualTo(SecurityService.ANONYMOUS);
        assertThat(fromMissingContext).isEqualTo(SecurityService.ANONYMOUS);
    }

    @Test
    void username_returnsAuthenticationNameOrAnonymous() {
        Authentication auth = new TestingAuthenticationToken("alice", "pwd");

        String directName = securityService.username(auth);
        String directAnonymous = securityService.username(null);
        String reactiveName = securityService.username()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(directName).isEqualTo("alice");
        assertThat(directAnonymous).isEqualTo(SecurityService.ANONYMOUS);
        assertThat(reactiveName).isEqualTo("alice");
    }

    @Test
    void clientId_returnsAnonymousWhenAttributesAreNullOrBlank() {
        BearerTokenAuthentication nullAttributesAuth = bearerAuthentication(null);
        BearerTokenAuthentication blankAttributesAuth = bearerAuthentication(Map.of("azp", "   ", "client_id", ""));

        assertThat(securityService.clientId(nullAttributesAuth)).isEqualTo(SecurityService.ANONYMOUS);
        assertThat(securityService.clientId(blankAttributesAuth)).isEqualTo(SecurityService.ANONYMOUS);
    }

    private BearerTokenAuthentication bearerAuthentication(Map<String, Object> tokenAttributes) {
        Map<String, Object> principalClaims = new HashMap<>();
        principalClaims.put(StandardClaimNames.SUB, "subject-1");

        if (tokenAttributes != null) {
            principalClaims.putAll(tokenAttributes);
        }

        var principal = new DefaultOAuth2AuthenticatedPrincipal(principalClaims, AuthorityUtils.NO_AUTHORITIES);
        var accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300)
        );
        return new BearerTokenAuthentication(principal, accessToken, AuthorityUtils.NO_AUTHORITIES);
    }
}

