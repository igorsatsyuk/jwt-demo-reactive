package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.dto.RequestAcceptedResponse;
import lt.satsyuk.dto.RequestStatusResponse;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import lt.satsyuk.repository.RequestRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RequestIntegrationIT extends AbstractIntegrationTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RequestRepository requestRepository;

    @MockitoBean
    private ReactiveOpaqueTokenIntrospector opaqueTokenIntrospector;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll()
                .then(clientRepository.deleteAll())
                .then(requestRepository.deleteAll())
                .block();
    }

    @Test
    void create_client_request_is_processed_by_scheduler_and_completes() {
        CreateClientRequest payload = new CreateClientRequest("John", "Doe", "+37069990001");

        RequestAcceptedResponse accepted = withRole("CLIENT_CREATE")
                .post()
                .uri("/api/clients")
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();
        assertThat(accepted.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(requestRepository.findById(accepted.requestId()).blockOptional()).isPresent();

        RequestStatusResponse completed = awaitTerminalStatus(accepted.requestId(), RequestStatus.COMPLETED);
        assertThat(completed.response()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) completed.response();
        assertThat(nested.get("code")).isEqualTo(0);
        assertThat(nested.get("message")).isEqualTo("OK");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) nested.get("data");
        assertThat(data.get("phone")).isEqualTo(payload.phone());

        Number clientIdValue = (Number) data.get("id");
        assertThat(clientIdValue).isNotNull();
        Long clientId = clientIdValue.longValue();

        Account account = accountRepository.findByClientId(clientId).blockOptional().orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo("0");
        assertThat(clientRepository.existsByPhone(payload.phone()).blockOptional().orElse(false)).isTrue();
    }

    @Test
    void create_client_request_duplicate_phone_becomes_failed_with_conflict_payload() {
        clientRepository.save(Client.builder()
                        .firstName("Jane")
                        .lastName("Roe")
                        .phone("+37069990002")
                        .build())
                .blockOptional()
                .orElseThrow();

        CreateClientRequest payload = new CreateClientRequest("John", "Doe", "+37069990002");

        RequestAcceptedResponse accepted = withRole("CLIENT_CREATE")
                .post()
                .uri("/api/clients")
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();
        assertThat(requestRepository.findById(accepted.requestId()).blockOptional()).isPresent();

        RequestStatusResponse failed = awaitTerminalStatus(accepted.requestId(), RequestStatus.FAILED);
        assertThat(failed.response()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) failed.response();
        assertThat(nested.get("code")).isEqualTo(AppResponse.ErrorCode.CONFLICT.getCode());
        assertThat(nested.get("message")).isEqualTo("Client with phone=+37069990002 already exists");
    }

    private RequestStatusResponse awaitTerminalStatus(UUID requestId, RequestStatus expectedTerminalStatus) {
        RequestStatusResponse[] holder = new RequestStatusResponse[1];

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    RequestStatusResponse current = withRole("CLIENT_CREATE")
                            .get()
                            .uri("/api/requests/{id}", requestId)
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(new ParameterizedTypeReference<AppResponse<RequestStatusResponse>>() {})
                            .returnResult()
                            .getResponseBody()
                            .data();

                    assertThat(current).isNotNull();
                    assertThat(current.status()).isEqualTo(expectedTerminalStatus);
                    assertThat(current.response()).isNotNull();
                    holder[0] = current;
                });

        return holder[0];
    }

    private WebTestClient withRole(String role) {
        OAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal(
                Map.of("sub", "integration-user"),
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        when(opaqueTokenIntrospector.introspect(anyString())).thenReturn(Mono.just(principal));

        return webTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer integration-token")
                .build();
    }
}

