package lt.satsyuk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.exception.RequestNotFoundException;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.RequestRepository;
import lt.satsyuk.testutil.ReclaimStatsTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestServiceTest {

    private final RequestRepository requestRepository = mock(RequestRepository.class);
    private final ClientService clientService = mock(ClientService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final RequestService requestService = new RequestService(
            requestRepository,
            clientService,
            new ObjectMapper(),
            messageService,
            meterRegistry
    );

    @Test
    void submitClientCreateRequest_failsWhenInsertDidNotPersistExactlyOneRow() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000001");

        when(requestRepository.insertRequest(any(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Mono.just(0));

        StepVerifier.create(requestService.submitClientCreateRequest(request))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Failed to persist async request"))
                .verify();
    }

    @Test
    void submitClientCreateRequest_returnsAcceptedWhenInsertPersistsOneRow() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000002");
        when(requestRepository.insertRequest(any(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(requestService.submitClientCreateRequest(request))
                .assertNext(response -> assertThat(response.status()).isEqualTo(RequestStatus.PENDING))
                .verifyComplete();
    }

    @Test
    void getRequestStatus_returnsMappedResponse() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Request request = Request.builder()
                .id(id)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.COMPLETED)
                .createdAt(now)
                .statusChangedAt(now)
                .responseData("{\"hello\":\"world\"}")
                .build();

        when(requestRepository.findById(id)).thenReturn(Mono.just(request));

        StepVerifier.create(requestService.getRequestStatus(id))
                .assertNext(response -> {
                    assertThat(response.requestId()).isEqualTo(id);
                    assertThat(response.type()).isEqualTo(RequestType.CLIENT_CREATE);
                    assertThat(response.status()).isEqualTo(RequestStatus.COMPLETED);
                    assertThat(response.response()).isInstanceOf(java.util.Map.class);
                })
                .verifyComplete();
    }

    @Test
    void getRequestStatus_returnsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(requestService.getRequestStatus(id))
                .expectError(RequestNotFoundException.class)
                .verify();
    }

    @Test
    void getRequestStatus_returnsNullResponseOnInvalidJson() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Request request = Request.builder()
                .id(id)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.FAILED)
                .createdAt(now)
                .statusChangedAt(now)
                .responseData("not-json")
                .build();

        when(requestRepository.findById(id)).thenReturn(Mono.just(request));

        StepVerifier.create(requestService.getRequestStatus(id))
                .assertNext(response -> assertThat(response.response()).isNull())
                .verifyComplete();
    }

    @Test
    void processClaimedRequest_marksCompletedOnSuccess() {
        UUID id = UUID.randomUUID();
        CreateClientRequest payload = new CreateClientRequest("John", "Doe", "+37060000003");
        Request request = Request.builder()
                .id(id)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PROCESSING)
                .requestData("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"phone\":\"+37060000003\"}")
                .build();

        when(clientService.create(payload)).thenReturn(Mono.just(new ClientResponse(1L, "John", "Doe", "+37060000003")));
        when(requestRepository.markCompleted(any(), anyString(), any())).thenReturn(Mono.just(1));

        Mono<Void> result = invokeMonoVoid(requestService, "processClaimedRequest", request);

        StepVerifier.create(result).verifyComplete();
        verify(requestRepository).markCompleted(any(), anyString(), any());
        verify(requestRepository, never()).markFailed(any(), anyString(), any());
        assertThat(meterRegistry.counter("request.worker.terminal_status", "status", "COMPLETED").count()).isEqualTo(1.0d);
        assertThat(meterRegistry.timer("request.worker.processing_duration", "terminal_status", "COMPLETED").count()).isEqualTo(1L);
    }

    @Test
    void processClaimedRequest_doesNotRecordCompletedMetricsWhenCompletionSkipped() {
        UUID id = UUID.randomUUID();
        CreateClientRequest payload = new CreateClientRequest("John", "Doe", "+37060000008");
        Request request = Request.builder()
                .id(id)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PROCESSING)
                .requestData("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"phone\":\"+37060000008\"}")
                .build();

        when(clientService.create(payload)).thenReturn(Mono.just(new ClientResponse(1L, "John", "Doe", "+37060000008")));
        when(requestRepository.markCompleted(any(), anyString(), any())).thenReturn(Mono.just(0));

        Mono<Void> result = invokeMonoVoid(requestService, "processClaimedRequest", request);

        StepVerifier.create(result).verifyComplete();
        assertThat(meterRegistry.counter("request.worker.terminal_status", "status", "COMPLETED").count()).isEqualTo(0.0d);
        assertThat(meterRegistry.timer("request.worker.processing_duration", "terminal_status", "COMPLETED").count()).isEqualTo(0L);
    }

    @Test
    void processClaimedRequest_marksFailedOnUnsupportedType() {
        UUID id = UUID.randomUUID();
        Request request = Request.builder()
                .id(id)
                .type(RequestType.OTHER)
                .status(RequestStatus.PROCESSING)
                .requestData("{}")
                .build();

        when(messageService.getMessage("api.error.internalServerError")).thenReturn("Internal server error");
        when(requestRepository.markFailed(any(), anyString(), any())).thenReturn(Mono.just(1));

        Mono<Void> result = invokeMonoVoid(requestService, "processClaimedRequest", request);

        StepVerifier.create(result).verifyComplete();
        verify(requestRepository).markFailed(any(), anyString(), any());
        assertThat(meterRegistry.counter("request.worker.terminal_status", "status", "FAILED").count()).isEqualTo(1.0d);
        assertThat(meterRegistry.timer("request.worker.processing_duration", "terminal_status", "FAILED").count()).isEqualTo(1L);
    }

    @Test
    void processClaimedRequest_doesNotRecordFailedMetricsWhenFailureUpdateSkipped() {
        UUID id = UUID.randomUUID();
        Request request = Request.builder()
                .id(id)
                .type(RequestType.OTHER)
                .status(RequestStatus.PROCESSING)
                .requestData("{}")
                .build();

        when(messageService.getMessage("api.error.internalServerError")).thenReturn("Internal server error");
        when(requestRepository.markFailed(any(), anyString(), any())).thenReturn(Mono.just(0));

        Mono<Void> result = invokeMonoVoid(requestService, "processClaimedRequest", request);

        StepVerifier.create(result).verifyComplete();
        assertThat(meterRegistry.counter("request.worker.terminal_status", "status", "FAILED").count()).isEqualTo(0.0d);
        assertThat(meterRegistry.timer("request.worker.processing_duration", "terminal_status", "FAILED").count()).isEqualTo(0L);
    }

    @Test
    void toWorkerError_mapsPhoneAlreadyExistsToConflict() {
        when(messageService.getMessage("error.client.phoneExists", new Object[]{"+37060000004"})).thenReturn("Phone exists");

        AppResponse<Void> error = invokeWorkerError(requestService, new PhoneAlreadyExistsException("+37060000004"));

        assertThat(error.code()).isEqualTo(AppResponse.ErrorCode.CONFLICT.getCode());
        assertThat(error.message()).isEqualTo("Phone exists");
    }

    @Test
    void toWorkerError_mapsUnexpectedErrorsToInternalServerError() {
        when(messageService.getMessage("api.error.internalServerError")).thenReturn("Internal server error");

        AppResponse<Void> error = invokeWorkerError(requestService, new IllegalStateException("boom"));

        assertThat(error.code()).isEqualTo(AppResponse.ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(error.message()).isEqualTo("Internal server error");
    }

    @Test
    void isRequestTableMissing_detectsByMessageAndCause() {
        boolean byMessage = invokeBoolean(requestService, "isRequestTableMissing", new RuntimeException("relation \"request\" does not exist"));
        boolean byCause = invokeBoolean(requestService, "isRequestTableMissing", new RuntimeException("top", new RuntimeException("relation \"request\" does not exist")));

        assertThat(byMessage).isTrue();
        assertThat(byCause).isTrue();
    }

    @Test
    void isConnectionClosedDuringShutdown_detectsBothPatterns() {
        boolean direct = invokeBoolean(requestService, "isConnectionClosedDuringShutdown", new RuntimeException("Failed to obtain R2DBC Connection"));
        boolean nested = invokeBoolean(requestService, "isConnectionClosedDuringShutdown", new RuntimeException("top", new RuntimeException("connection is closed")));

        assertThat(direct).isTrue();
        assertThat(nested).isTrue();
    }

    @Test
    void isTransientDbError_detectsKnownTransientCases() {
        boolean byType = invokeBoolean(requestService, "isTransientDbError", new DataAccessResourceFailureException("db"));
        boolean byMessage = invokeBoolean(requestService, "isTransientDbError", new RuntimeException("Connection reset"));

        assertThat(byType).isTrue();
        assertThat(byMessage).isTrue();
    }

    @Test
    void typedReadJson_throwsOnBlankInput() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(requestService, "readJson", "   ", CreateClientRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON payload is empty");
    }

    @Test
    void writeJson_throwsIllegalStateWhenMapperFails() {
        RequestService failingService = new RequestService(
                requestRepository,
                clientService,
                new FailingObjectMapper(),
                messageService,
                new SimpleMeterRegistry()
        );
        Object value = new Object();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(failingService, "writeJson", value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize object");
    }

    @Test
    void typedReadJson_throwsOnMalformedJson() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                requestService,
                "readJson",
                "{bad-json}",
                CreateClientRequest.class
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to deserialize JSON payload");
    }

    @Test
    void reclaimStaleProcessingRequests_returnsEmptyWhenTimeoutIsZero() {
        ReflectionTestUtils.setField(requestService, "workerProcessingTimeout", Duration.ZERO);

        Mono<Void> result = invokeMonoVoid(requestService, "reclaimStaleProcessingRequests", OffsetDateTime.now());

        StepVerifier.create(result).verifyComplete();
        verify(requestRepository, never()).reclaimStaleClientCreateRequests(any(), any());
    }

    @Test
    void reclaimStaleProcessingRequests_reclaimsAndRecordsMetricsWhenRowsFound() {
        ReflectionTestUtils.setField(requestService, "workerProcessingTimeout", Duration.ofMinutes(2));
        when(requestRepository.reclaimStaleClientCreateRequests(any(), any()))
                .thenReturn(Mono.just(ReclaimStatsTestUtils.reclaimStats(2, 45)));

        Mono<Void> result = invokeMonoVoid(requestService, "reclaimStaleProcessingRequests", OffsetDateTime.now());

        StepVerifier.create(result).verifyComplete();
        verify(requestRepository).reclaimStaleClientCreateRequests(any(), any());
        assertThat(meterRegistry.counter("request.worker.reclaimed_count").count()).isEqualTo(2.0d);
        assertThat(meterRegistry.summary("request.worker.stale_processing_age").count()).isEqualTo(1L);
    }

    @Test
    void markCompleted_completesWhenRowWasNotUpdated() {
        when(requestRepository.markCompleted(any(), anyString(), any())).thenReturn(Mono.just(0));

        Mono<Void> result = invokeMonoVoid(
                requestService,
                "markCompleted",
                UUID.randomUUID(),
                new ClientResponse(1L, "John", "Doe", "+37060000007")
        );

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void markFailed_completesWhenRowWasNotUpdated() {
        when(messageService.getMessage("api.error.internalServerError")).thenReturn("Internal server error");
        when(requestRepository.markFailed(any(), anyString(), any())).thenReturn(Mono.just(0));

        Mono<Void> result = invokeMonoVoid(
                requestService,
                "markFailed",
                UUID.randomUUID(),
                new IllegalStateException("boom")
        );

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void processPendingRequests_returnsImmediatelyWhenWorkerAlreadyRunning() {
        AtomicBoolean workerRunning = (AtomicBoolean) ReflectionTestUtils.getField(requestService, "workerRunning");
        assertThat(workerRunning).isNotNull();
        workerRunning.set(true);

        requestService.processPendingRequests();

        verify(requestRepository, never()).claimPendingClientCreateBatch(any(Integer.class), any());
    }

    @Test
    void processPendingRequests_executesBatchAndResetsRunningFlag() {
        AtomicBoolean workerRunning = (AtomicBoolean) ReflectionTestUtils.getField(requestService, "workerRunning");
        assertThat(workerRunning).isNotNull();

        ReflectionTestUtils.setField(requestService, "workerProcessingTimeout", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(requestService, "workerBatchSize", 10);
        ReflectionTestUtils.setField(requestService, "workerRetryMaxAttempts", 1L);
        ReflectionTestUtils.setField(requestService, "workerRetryBackoffMs", 1L);

        when(requestRepository.reclaimStaleClientCreateRequests(any(), any()))
                .thenReturn(Mono.just(ReclaimStatsTestUtils.reclaimStats(0, 0)));
        when(requestRepository.claimPendingClientCreateBatch(any(Integer.class), any())).thenReturn(Flux.empty());

        requestService.processPendingRequests();

        assertThat(workerRunning.get()).isFalse();
        verify(requestRepository).claimPendingClientCreateBatch(any(Integer.class), any());
        assertThat(meterRegistry.summary("request.worker.claim_batch_size").count()).isEqualTo(1L);
    }

    @Test
    void recordClaimLag_skipsWhenCreatedAtMissing() {
        Request request = Request.builder()
                .id(UUID.randomUUID())
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PENDING)
                .createdAt(null)
                .build();

        ReflectionTestUtils.invokeMethod(
                requestService,
                "recordClaimLag",
                OffsetDateTime.now(),
                request
        );

        assertThat(meterRegistry.summary("request.worker.claim_lag_seconds").count()).isEqualTo(0L);
    }

    @Test
    void isTransientDbError_returnsFalseForNonTransientError() {
        boolean result = invokeBoolean(requestService, "isTransientDbError", new RuntimeException("other"));

        assertThat(result).isFalse();
    }

    private static class FailingObjectMapper extends ObjectMapper {
        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("boom") {
            };
        }
    }

    private static Mono<Void> invokeMonoVoid(Object target, String method, Object... args) {
        Mono<Void> result = ReflectionTestUtils.invokeMethod(target, method, args);
        assertThat(result).isNotNull();
        return result;
    }

    private static boolean invokeBoolean(Object target, String method, Object... args) {
        Boolean result = ReflectionTestUtils.invokeMethod(target, method, args);
        assertThat(result).isNotNull();
        return result;
    }

    private static AppResponse<Void> invokeWorkerError(Object target, Throwable ex) {
        AppResponse<Void> result = ReflectionTestUtils.invokeMethod(target, "toWorkerError", ex);
        assertThat(result).isNotNull();
        return result;
    }

}
