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
import org.springframework.http.HttpStatus;
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

    protected static final String DOE = "Doe";
    protected static final String JOHN = "John";
    protected static final String CLIENT_CREATE_ROLE = "CLIENT_CREATE";
    protected static final String CLIENT_GET_ROLE = "CLIENT_GET";
    protected static final String JANE = "Jane";
    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;
    private final RequestRepository requestRepository;

    @Autowired
    RequestIntegrationIT(
            ClientRepository clientRepository,
            AccountRepository accountRepository,
            RequestRepository requestRepository
    ) {
        this.clientRepository = clientRepository;
        this.accountRepository = accountRepository;
        this.requestRepository = requestRepository;
    }

    @MockitoBean
    private ReactiveOpaqueTokenIntrospector opaqueTokenIntrospector;

    @BeforeEach
    void setUp() {
        requestRepository.deleteAll()
                .then(accountRepository.deleteAll())
                .then(clientRepository.deleteAll())
                .block();
    }

    @Test
    void create_client_request_is_processed_by_scheduler_and_completes() {
        CreateClientRequest payload = new CreateClientRequest(JOHN, DOE, "+37069990001", null);

        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
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
        assertThat(nested)
                .containsEntry("code", 0)
                .containsEntry("message", "OK");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) nested.get("data");
        assertThat(data).containsEntry("phone", payload.phone());

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
                        .firstName(JANE)
                        .lastName("Roe")
                        .phone("+37069990002")
                        .build())
                .blockOptional()
                .orElseThrow();

        CreateClientRequest payload = new CreateClientRequest(JOHN, DOE, "+37069990002", null);

        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
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
        assertThat(nested)
                .containsEntry("code", AppResponse.ErrorCode.CONFLICT.getCode())
                .containsEntry("message", "Client with phone=+37069990002 already exists");
    }

    @Test
    void get_request_status_not_found_returns_404() {
        UUID unknownId = UUID.randomUUID();

        AppResponse<Void> response = withRole(CLIENT_CREATE_ROLE)
                .get()
                .uri(API_REQUESTS_ID, unknownId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.NOT_FOUND.getCode());
        assertThat(response.message()).isEqualTo("Request with id=" + unknownId + " not found");
    }

    @Test
    void get_request_status_invalid_uuid_returns_400() {
        AppResponse<Void> response = withRole(CLIENT_CREATE_ROLE)
                .get()
                .uri(API_REQUESTS_ID, "not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).isEqualTo("Invalid value: not-a-uuid");
    }

    @Test
    void get_request_status_truncated_uuid_returns_400() {
        AppResponse<Void> response = withRole(CLIENT_CREATE_ROLE)
                .get()
                .uri(API_REQUESTS_ID, "550e8400-e29b-41d4-a716")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    void create_client_request_invalid_idempotencyKey_returns_400() {
        String invalidBody = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"phone\":\"+37069990020\",\"idempotencyKey\":\"not-a-uuid\"}";

        AppResponse<Void> response = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invalidBody)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    void get_request_status_without_required_role_returns_403() {
        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(new CreateClientRequest(JANE, DOE, "+37069990003", null))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {
                })
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();

        AppResponse<Void> response = withRole(CLIENT_GET_ROLE)
                .get()
                .uri(API_REQUESTS_ID, accepted.requestId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.FORBIDDEN.getCode());

        // Do not leak pending async work into the next test's cleanup phase.
        awaitTerminalStatus(accepted.requestId(), RequestStatus.COMPLETED);
    }

    @Test
    void get_request_status_is_idempotent_after_terminal_state() {
        CreateClientRequest payload = new CreateClientRequest(JOHN, "Idempotent", "+37069990004", null);
        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {
                })
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();
        RequestStatusResponse terminal = awaitTerminalStatus(accepted.requestId(), RequestStatus.COMPLETED);

        RequestStatusResponse first = getRequestStatus(accepted.requestId());
        RequestStatusResponse second = getRequestStatus(accepted.requestId());

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.requestId()).isEqualTo(accepted.requestId());
        assertThat(second.requestId()).isEqualTo(accepted.requestId());
        assertThat(first.status()).isEqualTo(terminal.status());
        assertThat(second.status()).isEqualTo(terminal.status());
        assertThat(first.response()).isEqualTo(second.response());
    }

    @Test
    void create_client_request_with_idempotencyKey_uses_key_as_request_id() {
        UUID idempotencyKey = UUID.randomUUID();
        CreateClientRequest payload = new CreateClientRequest(JOHN, DOE, "+37069990005", idempotencyKey);

        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();
        assertThat(accepted.requestId()).isEqualTo(idempotencyKey);
        assertThat(accepted.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(requestRepository.findById(idempotencyKey).blockOptional()).isPresent();

        RequestStatusResponse completed = awaitTerminalStatus(idempotencyKey, RequestStatus.COMPLETED);
        assertThat(completed.response()).isInstanceOf(Map.class);
    }

    @Test
    void create_client_request_duplicate_idempotencyKey_returns_existing_request() {
        UUID idempotencyKey = UUID.randomUUID();
        CreateClientRequest payload = new CreateClientRequest(JOHN, DOE, "+37069990006", idempotencyKey);

        RequestAcceptedResponse firstAccepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(firstAccepted).isNotNull();
        assertThat(firstAccepted.requestId()).isEqualTo(idempotencyKey);

        RequestAcceptedResponse secondAccepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(secondAccepted).isNotNull();
        assertThat(secondAccepted.requestId()).isEqualTo(firstAccepted.requestId());

        awaitTerminalStatus(idempotencyKey, RequestStatus.COMPLETED);
    }

    @Test
    void create_client_request_duplicate_idempotencyKey_different_payload_returns_conflict() {
        UUID idempotencyKey = UUID.randomUUID();
        CreateClientRequest payload1 = new CreateClientRequest(JOHN, DOE, "+37069990010", idempotencyKey);
        CreateClientRequest payload2 = new CreateClientRequest(JANE, "Roe", "+37069990011", idempotencyKey);

        withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload1)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult();

        AppResponse<Void> conflictResponse = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload2)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(conflictResponse).isNotNull();
        assertThat(conflictResponse.code()).isEqualTo(AppResponse.ErrorCode.CONFLICT.getCode());
    }

    @Test
    void create_client_request_without_idempotencyKey_generates_new_id() {
        CreateClientRequest payload = new CreateClientRequest(JOHN, DOE, "+37069990007", null);

        RequestAcceptedResponse accepted = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted).isNotNull();
        assertThat(accepted.requestId()).isNotNull();
        assertThat(accepted.status()).isEqualTo(RequestStatus.PENDING);

        awaitTerminalStatus(accepted.requestId(), RequestStatus.COMPLETED);
    }

    @Test
    void create_client_request_with_idempotencyKey_different_keys_create_separate_requests() {
        UUID key1 = UUID.randomUUID();
        UUID key2 = UUID.randomUUID();
        CreateClientRequest payload1 = new CreateClientRequest(JOHN, DOE, "+37069990008", key1);
        CreateClientRequest payload2 = new CreateClientRequest(JANE, "Roe", "+37069990009", key2);

        RequestAcceptedResponse accepted1 = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload1)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        RequestAcceptedResponse accepted2 = withRole(CLIENT_CREATE_ROLE)
                .post()
                .uri(API_CLIENTS)
                .bodyValue(payload2)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestAcceptedResponse>>() {})
                .returnResult()
                .getResponseBody()
                .data();

        assertThat(accepted1).isNotNull();
        assertThat(accepted2).isNotNull();
        assertThat(accepted1.requestId()).isEqualTo(key1);
        assertThat(accepted2.requestId()).isEqualTo(key2);
        assertThat(accepted1.requestId()).isNotEqualTo(accepted2.requestId());

        awaitTerminalStatus(key1, RequestStatus.COMPLETED);
        awaitTerminalStatus(key2, RequestStatus.COMPLETED);
    }

    private RequestStatusResponse awaitTerminalStatus(UUID requestId, RequestStatus expectedTerminalStatus) {
        RequestStatusResponse[] holder = new RequestStatusResponse[1];

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    RequestStatusResponse current = withRole(CLIENT_CREATE_ROLE)
                            .get()
                            .uri(API_REQUESTS_ID, requestId)
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

    private RequestStatusResponse getRequestStatus(UUID requestId) {
        AppResponse<RequestStatusResponse> response = withRole(CLIENT_CREATE_ROLE)
                .get()
                .uri(API_REQUESTS_ID, requestId)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(new ParameterizedTypeReference<AppResponse<RequestStatusResponse>>() {
                })
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        return response.data();
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

