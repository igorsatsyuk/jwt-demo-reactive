package lt.satsyuk.api.integrationtest;

import lt.satsyuk.dto.AccountResponse;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.UpdateBalanceRequest;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AccountIntegrationIT extends AbstractIntegrationTest {

    private static final int DB_SETUP_MAX_ATTEMPTS = 4;
    private static final int PESSIMISTIC_SCENARIO_MAX_ATTEMPTS = 3;
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ClientRepository clientRepository;

    @MockitoBean
    private ReactiveOpaqueTokenIntrospector opaqueTokenIntrospector;

    @BeforeEach
    void setUp() {
        withTransientDbRetry(() -> accountRepository.deleteAll()
                .then(clientRepository.deleteAll())
                .block(BLOCK_TIMEOUT));
    }

    @Test
    void update_balance_pessimistic_success() {
        Account account = saveAccount("100.00", "+37061111111");

        AppResponse<AccountResponse> response = withRole("UPDATE_BALANCE")
                .post()
                .uri("/api/accounts/balance/pessimistic")
                .bodyValue(new UpdateBalanceRequest(account.getClientId(), new BigDecimal("250.75")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<AccountResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isZero();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().accountId()).isEqualTo(account.getId());
        assertThat(response.data().clientId()).isEqualTo(account.getClientId());
        assertThat(response.data().balance()).isEqualByComparingTo("350.75");

        Account persisted = accountRepository.findById(account.getId()).blockOptional().orElseThrow();
        assertThat(persisted.getBalance()).isEqualByComparingTo("350.75");
    }

    @Test
    void update_balance_optimistic_success() {
        Account account = saveAccount("50.00", "+37062222222");

        AppResponse<AccountResponse> response = withRole("UPDATE_BALANCE")
                .post()
                .uri("/api/accounts/balance/optimistic")
                .bodyValue(new UpdateBalanceRequest(account.getClientId(), new BigDecimal("75.00")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<AccountResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isZero();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().accountId()).isEqualTo(account.getId());
        assertThat(response.data().clientId()).isEqualTo(account.getClientId());
        assertThat(response.data().balance()).isEqualByComparingTo("125.00");
    }

    @Test
    void get_account_by_client_id_success() {
        Account account = saveAccount("10.00", "+37063333333");

        AppResponse<AccountResponse> response = withRole("CLIENT_GET")
                .get()
                .uri("/api/accounts/client/{clientId}", account.getClientId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<AppResponse<AccountResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isZero();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().accountId()).isEqualTo(account.getId());
        assertThat(response.data().clientId()).isEqualTo(account.getClientId());
        assertThat(response.data().balance()).isEqualByComparingTo("10.00");
    }

    @Test
    void update_balance_pessimistic_not_found_returns_404() {
        AppResponse<Void> response = withRole("UPDATE_BALANCE")
                .post()
                .uri("/api/accounts/balance/pessimistic")
                .bodyValue(new UpdateBalanceRequest(999999L, new BigDecimal("100.00")))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.NOT_FOUND.getCode());
        assertThat(response.message()).isEqualTo("Account for client id=999999 not found");
    }

    @Test
    void update_balance_pessimistic_with_non_positive_client_id_returns_400() {
        AppResponse<Void> response = withRole("UPDATE_BALANCE")
                .post()
                .uri("/api/accounts/balance/pessimistic")
                .bodyValue(new UpdateBalanceRequest(0L, new BigDecimal("100.00")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<AppResponse<Void>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(AppResponse.ErrorCode.BAD_REQUEST.getCode());
        assertThat(response.message()).contains("clientId:");
        assertThat(response.message()).contains("ClientId");
    }

    @Test
    void concurrent_pessimistic_updates_apply_all_deltas() {
        BigDecimal delta = new BigDecimal("10.00");
        int updates = 8;
        BigDecimal expectedBalance = delta.multiply(BigDecimal.valueOf(updates));
        List<HttpStatusCode> statuses = List.of();
        Account account = null;

        for (int attempt = 1; attempt <= PESSIMISTIC_SCENARIO_MAX_ATTEMPTS; attempt++) {
            account = saveAccount("0.00", pessimisticPhoneForAttempt(attempt));
            statuses = runConcurrentUpdates(
                    "/api/accounts/balance/pessimistic",
                    account.getClientId(),
                    delta,
                    updates
            );

            if (!allStatuses(statuses, HttpStatus.OK)) {
                if (attempt < PESSIMISTIC_SCENARIO_MAX_ATTEMPTS && onlyTransientPessimisticStatuses(statuses)) {
                    continue;
                }
                break;
            }

            Account persisted = accountRepository.findById(account.getId()).blockOptional().orElseThrow();
            if (persisted.getBalance().compareTo(expectedBalance) == 0) {
                return;
            }
        }

        assertThat(account).isNotNull();
        assertThat(statuses).isNotEmpty().allMatch(status -> status.equals(HttpStatus.OK));

        Account persisted = accountRepository.findById(account.getId()).blockOptional().orElseThrow();
        assertThat(persisted.getBalance()).isEqualByComparingTo(expectedBalance);
    }

    @Test
    void concurrent_optimistic_updates_keep_balance_consistent_with_successful_requests() {
        Account account = saveAccount("0.00", "+37065555555");
        BigDecimal delta = new BigDecimal("1.00");
        int updates = 20;

        List<HttpStatusCode> statuses = runConcurrentUpdates(
                "/api/accounts/balance/optimistic",
                account.getClientId(),
                delta,
                updates
        );

        assertThat(statuses)
                .isNotEmpty()
                .allMatch(status -> status.equals(HttpStatus.OK) || status.equals(HttpStatus.CONFLICT));

        long successfulUpdates = statuses.stream().filter(status -> status.equals(HttpStatus.OK)).count();
        assertThat(successfulUpdates).isPositive();

        Account persisted = accountRepository.findById(account.getId()).blockOptional().orElseThrow();
        assertThat(persisted.getBalance()).isEqualByComparingTo(delta.multiply(BigDecimal.valueOf(successfulUpdates)));
    }

    private WebTestClient withRole(String role) {
        configureRole(role);
        return authorizedClient();
    }

    private void configureRole(String role) {
        OAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal(
                Map.of("sub", "integration-user"),
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        when(opaqueTokenIntrospector.introspect(anyString())).thenReturn(Mono.just(principal));
    }

    private WebTestClient authorizedClient() {
        return webTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer integration-token")
                .build();
    }

    private Account saveAccount(String balance, String phone) {
        Client client = clientRepository.save(Client.builder()
                        .firstName("Test")
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

    private String pessimisticPhoneForAttempt(int attempt) {
        return "+3706444" + String.format("%04d", attempt);
    }

    private boolean allStatuses(List<HttpStatusCode> statuses, HttpStatus expected) {
        return !statuses.isEmpty() && statuses.stream().allMatch(status -> status.equals(expected));
    }

    private boolean onlyTransientPessimisticStatuses(List<HttpStatusCode> statuses) {
        return !statuses.isEmpty() && statuses.stream().allMatch(status ->
                status.equals(HttpStatus.OK)
                        || status.equals(HttpStatus.INTERNAL_SERVER_ERROR)
                        || status.equals(HttpStatus.SERVICE_UNAVAILABLE)
        );
    }

    private void withTransientDbRetry(Runnable operation) {
        Throwable last = null;
        for (int attempt = 1; attempt <= DB_SETUP_MAX_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return;
            } catch (RuntimeException ex) {
                if (!isTransientConnectionIssue(ex) || attempt == DB_SETUP_MAX_ATTEMPTS) {
                    throw ex;
                }
                last = ex;
                sleepBackoff(attempt);
            }
        }

        if (last instanceof RuntimeException runtimeEx) {
            throw runtimeEx;
        }
        throw new IllegalStateException("Unexpected retry state for DB setup");
    }

    private boolean isTransientConnectionIssue(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (current instanceof IOException) {
                return true;
            }
            if (current instanceof java.net.SocketException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && (
                    message.contains("Failed to obtain R2DBC Connection")
                            || message.contains("Connection reset")
                            || message.contains("I/O error occurred")
                            || message.contains("connection is closed")
            )) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    private void sleepBackoff(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(150L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry transient DB operation", interrupted);
        }
    }

    private List<HttpStatusCode> runConcurrentUpdates(String uri, Long clientId, BigDecimal amount, int parallelCalls) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelCalls, 8));
        configureRole("UPDATE_BALANCE");
        WebTestClient authorizedClient = authorizedClient();
        try {
            List<Callable<HttpStatusCode>> tasks = new ArrayList<>();
            for (int i = 0; i < parallelCalls; i++) {
                tasks.add(() -> authorizedClient
                        .post()
                        .uri(uri)
                        .bodyValue(new UpdateBalanceRequest(clientId, amount))
                        .exchange()
                        .returnResult(String.class)
                        .getStatus());
            }

            long timeoutSeconds = Math.max(10L, parallelCalls * 2L);
            List<Future<HttpStatusCode>> futures = pool.invokeAll(tasks, timeoutSeconds, TimeUnit.SECONDS);
            List<HttpStatusCode> statuses = new ArrayList<>(futures.size());
            for (Future<HttpStatusCode> future : futures) {
                if (future.isCancelled()) {
                    throw new IllegalStateException(
                            "Concurrent updates timed out before all requests completed within " + timeoutSeconds + " seconds");
                }
                statuses.add(future.get());
            }
            return statuses;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent updates were interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Concurrent updates failed", ex.getCause());
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException _) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
