package lt.satsyuk.api.integrationtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.satsyuk.api.util.TestTextUtils;
import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.RequestRepository;
import lt.satsyuk.service.ClientService;
import lt.satsyuk.service.RequestService;
import lt.satsyuk.testutil.ReclaimStatsTestUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "app.request.worker.interval-ms=600000",
        "app.request.worker.initial-delay-ms=600000",
        "app.request.worker.retry.max-attempts=2",
        "app.request.worker.retry.backoff-ms=25",
        "app.request.worker.batch-size=1"
})
@ExtendWith(OutputCaptureExtension.class)
class RequestWorkerRetryIT extends AbstractIntegrationTest {

    @Autowired
    private RequestService requestService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RequestRepository requestRepository;

    @MockitoBean
    private ClientService clientService;

    @Test
    void worker_retries_on_transient_db_error_and_eventually_completes() throws Exception {
        CreateClientRequest payload = new CreateClientRequest("John", "Retry", "+37068880001");
        UUID requestId = UUID.randomUUID();
        Request pending = Request.builder()
                .id(requestId)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PROCESSING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .statusChangedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .requestData(objectMapper.writeValueAsString(payload))
                .build();

        AtomicInteger subscribeAttempts = new AtomicInteger(0);

        when(requestRepository.reclaimStaleClientCreateRequests(any(), any()))
                .thenReturn(Mono.just(ReclaimStatsTestUtils.reclaimStats(0, 0)));

        when(requestRepository.claimPendingClientCreateBatch(anyInt(), any()))
                .thenReturn(Flux.defer(() -> {
                    if (subscribeAttempts.getAndIncrement() == 0) {
                        return Flux.error(new DataAccessResourceFailureException("Failed to obtain R2DBC Connection"));
                    }
                    return Flux.just(pending);
                }));

        when(clientService.create(any(CreateClientRequest.class)))
                .thenReturn(Mono.just(new ClientResponse(101L, payload.firstName(), payload.lastName(), payload.phone())));

        when(requestRepository.markCompleted(eq(requestId), anyString(), any()))
                .thenReturn(Mono.just(1));

        requestService.processPendingRequests();

        Awaitility.await()
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(subscribeAttempts.get()).isGreaterThanOrEqualTo(2);
                    verify(requestRepository, times(1)).claimPendingClientCreateBatch(anyInt(), any());
                    verify(requestRepository, times(1)).markCompleted(eq(requestId), anyString(), any());
                    verify(requestRepository, never()).markFailed(any(), anyString(), any());
                });
    }

    @Test
    void worker_exhausts_retry_and_logs_final_error_without_mark_completed(CapturedOutput output) {
        AtomicInteger subscribeAttempts = new AtomicInteger(0);

        when(requestRepository.reclaimStaleClientCreateRequests(any(), any()))
                .thenReturn(Mono.just(ReclaimStatsTestUtils.reclaimStats(0, 0)));

        when(requestRepository.claimPendingClientCreateBatch(anyInt(), any()))
                .thenReturn(Flux.defer(() -> {
                    subscribeAttempts.incrementAndGet();
                    return Flux.error(new DataAccessResourceFailureException("Transient network timeout"));
                }));

        requestService.processPendingRequests();

        Awaitility.await()
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(subscribeAttempts.get()).isGreaterThanOrEqualTo(3);
                    verify(requestRepository, times(1)).claimPendingClientCreateBatch(anyInt(), any());
                    verify(clientService, never()).create(any(CreateClientRequest.class));
                    verify(requestRepository, never()).markCompleted(any(), anyString(), any());
                    verify(requestRepository, never()).markFailed(any(), anyString(), any());
                    assertThat(output.getOut()).contains("Request worker transient DB error, retry 1/2");
                    assertThat(output.getOut()).contains("Request worker transient DB error, retry 2/2");
                    assertThat(TestTextUtils.countOccurrences(output.getOut(), "Request worker transient DB error, retry ")).isEqualTo(2);
                    assertThat(output.getOut()).contains("Request worker iteration failed");
                });
    }

    @Test
    void worker_does_not_retry_on_non_transient_error_and_logs_final_error(CapturedOutput output) {
        AtomicInteger subscribeAttempts = new AtomicInteger(0);

        when(requestRepository.reclaimStaleClientCreateRequests(any(), any()))
                .thenReturn(Mono.just(ReclaimStatsTestUtils.reclaimStats(0, 0)));

        when(requestRepository.claimPendingClientCreateBatch(anyInt(), any()))
                .thenReturn(Flux.defer(() -> {
                    subscribeAttempts.incrementAndGet();
                    return Flux.error(new IllegalStateException("Non-transient processing error"));
                }));

        requestService.processPendingRequests();

        Awaitility.await()
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(subscribeAttempts.get()).isEqualTo(1);
                    verify(requestRepository, times(1)).claimPendingClientCreateBatch(anyInt(), any());
                    verify(clientService, never()).create(any(CreateClientRequest.class));
                    verify(requestRepository, never()).markCompleted(any(), anyString(), any());
                    verify(requestRepository, never()).markFailed(any(), anyString(), any());
                    assertThat(output.getOut()).contains("Request worker iteration failed");
                });
    }

}

