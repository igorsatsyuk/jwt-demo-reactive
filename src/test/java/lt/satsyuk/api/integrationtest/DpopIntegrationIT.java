package lt.satsyuk.api.integrationtest;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lt.satsyuk.api.util.WireMockIntegrationTest;
import lt.satsyuk.dto.AccountResponse;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.KeycloakTokenResponse;
import lt.satsyuk.dto.LoginRequest;
import lt.satsyuk.dto.LogoutRequest;
import lt.satsyuk.dto.RefreshRequest;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

class DpopIntegrationIT extends WireMockIntegrationTest {

    private static final String DPOP_HEADER = "DPoP";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ClientRepository clientRepository;

    @BeforeEach
    void setUpData() {
        accountRepository.deleteAll()
                .then(clientRepository.deleteAll())
                .block();
    }

    @Test
    void login_forwards_dpop_header_to_keycloak() {
        String proof = "login-proof";

        stubFor(post(urlEqualTo(TOKEN_PATH))
                .withHeader(DPOP_HEADER, equalTo(proof))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "access_token": "access",
                                  "refresh_token": "refresh",
                                  "token_type": "DPoP"
                                }
                                """)));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result = webTestClient.post()
                .uri("/api/auth/login")
                .header(DPOP_HEADER, proof)
                .bodyValue(new LoginRequest("user", "password", "test-client", "test-secret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();

        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().data()).isNotNull();
        assertThat(result.getResponseBody().data().getTokenType()).isEqualTo("DPoP");
        verify(postRequestedFor(urlEqualTo(TOKEN_PATH)).withHeader(DPOP_HEADER, equalTo(proof)));
    }

    @Test
    void refresh_forwards_dpop_header_to_keycloak() {
        String proof = "refresh-proof";

        stubFor(post(urlEqualTo(TOKEN_PATH))
                .withHeader(DPOP_HEADER, equalTo(proof))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "access_token": "new-access",
                                  "refresh_token": "new-refresh",
                                  "token_type": "DPoP"
                                }
                                """)));

        EntityExchangeResult<AppResponse<KeycloakTokenResponse>> result = webTestClient.post()
                .uri("/api/auth/refresh")
                .header(DPOP_HEADER, proof)
                .bodyValue(new RefreshRequest("refresh-token", "test-client", "test-secret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<KeycloakTokenResponse>>() {})
                .returnResult();

        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().data()).isNotNull();
        assertThat(result.getResponseBody().data().getTokenType()).isEqualTo("DPoP");
        verify(postRequestedFor(urlEqualTo(TOKEN_PATH)).withHeader(DPOP_HEADER, equalTo(proof)));
    }

    @Test
    void logout_forwards_dpop_header_to_keycloak() {
        String proof = "logout-proof";

        stubFor(post(urlEqualTo(LOGOUT_PATH))
                .withHeader(DPOP_HEADER, equalTo(proof))
                .willReturn(aResponse().withStatus(204)));

        EntityExchangeResult<AppResponse<Void>> result = webTestClient.post()
                .uri("/api/auth/logout")
                .header(DPOP_HEADER, proof)
                .bodyValue(new LogoutRequest("refresh-token", "test-client", "test-secret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();

        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().code()).isZero();
        verify(postRequestedFor(urlEqualTo(LOGOUT_PATH)).withHeader(DPOP_HEADER, equalTo(proof)));
    }

    @Test
    void dpop_bound_token_with_valid_proof_returns_ok() throws Exception {
        Account account = saveAccount("10.00", "+37060000001");
        String accessToken = "bound-access-token";

        RSAKey key = generateRsaKey();
        String jkt = key.toPublicJWK().computeThumbprint().toString();
        stubIntrospectionWithJkt(jkt);

        String uri = absoluteUrl("/api/accounts/client/" + account.getClientId());
        String proof = createProof(key, "GET", uri, accessToken, UUID.randomUUID().toString());

        EntityExchangeResult<AppResponse<AccountResponse>> result = webTestClient.get()
                .uri("/api/accounts/client/{clientId}", account.getClientId())
                .header(AUTHORIZATION, "DPoP " + accessToken)
                .header(DPOP_HEADER, proof)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<AccountResponse>>() {})
                .returnResult();

        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().code()).isZero();
        assertThat(result.getResponseBody().data()).isNotNull();
        assertThat(result.getResponseBody().data().clientId()).isEqualTo(account.getClientId());
    }

    @Test
    void dpop_bound_token_without_proof_returns_unauthorized() {
        Account account = saveAccount("10.00", "+37060000002");
        stubIntrospectionWithJkt(randomJkt());

        EntityExchangeResult<AppResponse<Void>> result = webTestClient.get()
                .uri("/api/accounts/client/{clientId}", account.getClientId())
                .header(AUTHORIZATION, "DPoP bound-access-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();

        assertUnauthorized(result);
    }

    @Test
    void dpop_bound_token_with_replayed_proof_returns_unauthorized_on_second_request() throws Exception {
        Account account = saveAccount("10.00", "+37060000003");
        String accessToken = "bound-access-token";

        RSAKey key = generateRsaKey();
        String jkt = key.toPublicJWK().computeThumbprint().toString();
        stubIntrospectionWithJkt(jkt);

        String uri = absoluteUrl("/api/accounts/client/" + account.getClientId());
        String proof = createProof(key, "GET", uri, accessToken, "replay-jti");

        webTestClient.get()
                .uri("/api/accounts/client/{clientId}", account.getClientId())
                .header(AUTHORIZATION, "DPoP " + accessToken)
                .header(DPOP_HEADER, proof)
                .exchange()
                .expectStatus().isOk();

        EntityExchangeResult<AppResponse<Void>> replay = webTestClient.get()
                .uri("/api/accounts/client/{clientId}", account.getClientId())
                .header(AUTHORIZATION, "DPoP " + accessToken)
                .header(DPOP_HEADER, proof)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult();

        assertUnauthorized(replay);
    }

    private void stubIntrospectionWithJkt(String jkt) {
        stubFor(post(urlEqualTo(INTROSPECT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "active": true,
                                  "username": "user",
                                  "client_id": "spring-app",
                                  "azp": "spring-app",
                                  "cnf": {"jkt": "%s"},
                                  "realm_access": {"roles": ["CLIENT_GET"]},
                                  "resource_access": {"spring-app": {"roles": ["CLIENT_GET"]}}
                                }
                                """.formatted(jkt))));
    }

    private void assertUnauthorized(EntityExchangeResult<AppResponse<Void>> result) {
        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().code()).isEqualTo(AppResponse.ErrorCode.UNAUTHORIZED.getCode());
        assertThat(result.getResponseBody().message()).isEqualTo(AppResponse.ErrorCode.UNAUTHORIZED.getDescription());
    }

    private Account saveAccount(String balance, String phone) {
        Client client = clientRepository.save(Client.builder()
                        .firstName("Dpop")
                        .lastName("User")
                        .phone(phone)
                        .build())
                .blockOptional()
                .orElseThrow();

        return accountRepository.save(Account.builder()
                        .balance(new BigDecimal(balance))
                        .clientId(client.getId())
                        .build())
                .blockOptional()
                .orElseThrow();
    }

    private String randomJkt() {
        try {
            return generateRsaKey().toPublicJWK().computeThumbprint().toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String createProof(RSAKey key,
                               String method,
                               String uri,
                               String accessToken,
                               String jti) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(jti)
                .issueTime(Date.from(Instant.now()))
                .claim("htm", method)
                .claim("htu", uri)
                .claim("ath", ath(accessToken))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("dpop+jwt"))
                        .jwk(key.toPublicJWK())
                        .build(),
                claims
        );

        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt.serialize();
    }

    private String ath(String token) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return com.nimbusds.jose.util.Base64URL
                .encode(digest.digest(token.getBytes(StandardCharsets.US_ASCII)))
                .toString();
    }

    private RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

