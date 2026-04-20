package lt.satsyuk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.dto.RequestAcceptedResponse;
import lt.satsyuk.dto.RequestStatusResponse;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.exception.RequestNotFoundException;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.RequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class RequestService {

    private final RequestRepository requestRepository;
    private final ClientService clientService;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private final Counter reclaimedCount;
    private final DistributionSummary staleProcessingAgeSeconds;
    private final DistributionSummary claimLagSeconds;
    private final DistributionSummary claimBatchSize;
    private final Timer completedProcessingDuration;
    private final Timer failedProcessingDuration;
    private final Counter completedTerminalStatusCount;
    private final Counter failedTerminalStatusCount;

    public RequestService(RequestRepository requestRepository,
                          ClientService clientService,
                          ObjectMapper objectMapper,
                          MessageService messageService,
                          MeterRegistry meterRegistry) {
        this.requestRepository = requestRepository;
        this.clientService = clientService;
        this.objectMapper = objectMapper;
        this.messageService = messageService;
        this.reclaimedCount = Counter.builder("request.worker.reclaimed_count")
                .description("Number of stale PROCESSING requests reclaimed back to PENDING")
                .register(meterRegistry);
        this.staleProcessingAgeSeconds = DistributionSummary.builder("request.worker.stale_processing_age")
                .baseUnit("seconds")
                .description("Age in seconds of the oldest reclaimed stale PROCESSING request")
                .register(meterRegistry);
        this.claimLagSeconds = DistributionSummary.builder("request.worker.claim_lag_seconds")
                .baseUnit("seconds")
                .description("Time between request creation and claim by worker")
                .register(meterRegistry);
        this.claimBatchSize = DistributionSummary.builder("request.worker.claim_batch_size")
                .description("Number of requests claimed in a worker iteration")
                .register(meterRegistry);
        this.completedProcessingDuration = Timer.builder("request.worker.processing_duration")
                .description("Processing time for async worker requests")
                .tag("terminal_status", "COMPLETED")
                .register(meterRegistry);
        this.failedProcessingDuration = Timer.builder("request.worker.processing_duration")
                .description("Processing time for async worker requests")
                .tag("terminal_status", "FAILED")
                .register(meterRegistry);
        this.completedTerminalStatusCount = Counter.builder("request.worker.terminal_status")
                .description("Count of terminal statuses written by request worker")
                .tag("status", "COMPLETED")
                .register(meterRegistry);
        this.failedTerminalStatusCount = Counter.builder("request.worker.terminal_status")
                .description("Count of terminal statuses written by request worker")
                .tag("status", "FAILED")
                .register(meterRegistry);
    }

    @Value("${app.request.worker.batch-size:10}")
    private int workerBatchSize;

    @Value("${app.request.worker.max-concurrency:2}")
    private int workerMaxConcurrency;

    @Value("${app.request.worker.retry.max-attempts:3}")
    private long workerRetryMaxAttempts;

    @Value("${app.request.worker.retry.backoff-ms:200}")
    private long workerRetryBackoffMs;

    @Value("${app.request.worker.processing-timeout:2m}")
    private Duration workerProcessingTimeout;

    public Mono<RequestAcceptedResponse> submitClientCreateRequest(CreateClientRequest createClientRequest) {
        OffsetDateTime now = now();
        Request request = Request.builder()
                .id(UUID.randomUUID())
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PENDING)
                .createdAt(now)
                .statusChangedAt(now)
                .requestData(writeJson(createClientRequest))
                .build();

        return requestRepository.insertRequest(
                        request.getId(),
                        request.getType().name(),
                        request.getStatus().name(),
                        request.getCreatedAt(),
                        request.getStatusChangedAt(),
                        request.getRequestData(),
                        null
                )
                .flatMap(rows -> {
                    if (rows == 1) {
                        return Mono.just(new RequestAcceptedResponse(request.getId(), request.getStatus()));
                    }
                    return Mono.error(new IllegalStateException("Failed to persist async request"));
                });
    }

    @Scheduled(
            fixedDelayString = "${app.request.worker.interval-ms:${app.request.poll-interval-ms:2000}}",
            initialDelayString = "${app.request.worker.initial-delay-ms:500}"
    )
    public void processPendingRequests() {
        if (!workerRunning.compareAndSet(false, true)) {
            return;
        }

        claimAndProcessBatch()
                .retryWhen(workerRetrySpec())
                .doFinally(signal -> workerRunning.set(false))
                .subscribe(
                        null,
                        ex -> {
                            if (isRequestTableMissing(ex)) {
                                log.debug("Request worker skipped: request table is not ready yet");
                                return;
                            }
                            if (isConnectionClosedDuringShutdown(ex)) {
                                log.debug("Request worker stopped because DB connection is already closed");
                                return;
                            }
                            log.error("Request worker iteration failed", ex);
                        }
                );
    }

    public Mono<RequestStatusResponse> getRequestStatus(UUID requestId) {
        return requestRepository.findById(requestId)
                .switchIfEmpty(Mono.error(new RequestNotFoundException(requestId)))
                .map(request -> new RequestStatusResponse(
                        request.getId(),
                        request.getType(),
                        request.getStatus(),
                        request.getCreatedAt(),
                        request.getStatusChangedAt(),
                        readJson(request.getResponseData())
                ));
    }

    private Mono<Void> claimAndProcessBatch() {
        OffsetDateTime claimedAt = now();
        AtomicInteger claimedCount = new AtomicInteger();
        int maxConcurrency = resolveWorkerMaxConcurrency();
        return reclaimStaleProcessingRequests(claimedAt)
                .thenMany(requestRepository.claimPendingClientCreateBatch(workerBatchSize, claimedAt))
                .doOnNext(request -> {
                    claimedCount.incrementAndGet();
                    recordClaimLag(claimedAt, request);
                })
                .flatMapSequential(this::processClaimedRequest, maxConcurrency, 1)
                .then(Mono.fromRunnable(() -> claimBatchSize.record(claimedCount.get())));
    }

    private int resolveWorkerMaxConcurrency() {
        int normalizedBatchSize = Math.max(1, workerBatchSize);
        int normalizedConcurrency = Math.max(1, workerMaxConcurrency);
        return Math.min(normalizedBatchSize, normalizedConcurrency);
    }

    private void recordClaimLag(OffsetDateTime claimedAt, Request request) {
        OffsetDateTime createdAt = request.getCreatedAt();
        if (createdAt == null) {
            return;
        }
        double lagSeconds = Math.max(0d, Duration.between(createdAt, claimedAt).toMillis() / 1000d);
        claimLagSeconds.record(lagSeconds);
    }

    private Mono<Void> reclaimStaleProcessingRequests(OffsetDateTime now) {
        if (workerProcessingTimeout == null || workerProcessingTimeout.isZero() || workerProcessingTimeout.isNegative()) {
            return Mono.empty();
        }

        OffsetDateTime staleBefore = now.minus(workerProcessingTimeout);
        return requestRepository.reclaimStaleClientCreateRequests(staleBefore, now)
                .doOnNext(stats -> {
                    int reclaimed = stats.getReclaimedCount() != null ? stats.getReclaimedCount() : 0;
                    long maxAgeSeconds = stats.getMaxAgeSeconds() != null ? stats.getMaxAgeSeconds() : 0L;
                    if (reclaimed > 0) {
                        reclaimedCount.increment(reclaimed);
                        staleProcessingAgeSeconds.record(maxAgeSeconds);
                        log.warn(
                                "Request worker reclaimed {} stale PROCESSING request(s) older than {}; oldest age={}s",
                                reclaimed,
                                workerProcessingTimeout,
                                maxAgeSeconds
                        );
                    }
                })
                .then();
    }

    private Mono<Void> processClaimedRequest(Request request) {
        UUID requestId = request.getId();
        long startedNanos = System.nanoTime();
        return Mono.defer(() -> {
                    if (request.getType() != RequestType.CLIENT_CREATE) {
                        return Mono.error(new IllegalStateException("Unsupported request type: " + request.getType()));
                    }

                    CreateClientRequest payload = readJson(request.getRequestData(), CreateClientRequest.class);
                    return clientService.create(payload)
                            .flatMap(clientResponse -> markCompleted(requestId, clientResponse, startedNanos));
                })
                .onErrorResume(ex -> markFailed(requestId, ex, startedNanos));
    }

    private Mono<Void> markCompleted(UUID requestId, ClientResponse clientResponse, long startedNanos) {
        String responseJson = writeJson(AppResponse.ok(clientResponse));
        return requestRepository.markCompleted(requestId, responseJson, now())
                .doOnNext(updated -> {
                    if (updated == 1) {
                        completedTerminalStatusCount.increment();
                        if (startedNanos > 0L) {
                            completedProcessingDuration.record(Duration.ofNanos(System.nanoTime() - startedNanos));
                        }
                        log.info("Client creation request {} completed", requestId);
                    } else {
                        log.warn("Client creation request {} completion skipped because state changed", requestId);
                    }
                })
                .then();
    }


    private Mono<Void> markFailed(UUID requestId, Throwable ex, long startedNanos) {
        AppResponse<Void> errorPayload = toWorkerError(ex);
        String errorJson = writeJson(errorPayload);
        return requestRepository.markFailed(requestId, errorJson, now())
                .doOnNext(updated -> {
                    if (updated == 1) {
                        if (startedNanos > 0L) {
                            failedProcessingDuration.record(Duration.ofNanos(System.nanoTime() - startedNanos));
                        }
                        failedTerminalStatusCount.increment();
                        log.warn("Client creation request {} failed: {}", requestId, ex.getMessage());
                    } else {
                        log.warn("Client creation request {} failure update skipped because state changed", requestId);
                    }
                })
                .then();
    }

    private AppResponse<Void> toWorkerError(Throwable ex) {
        if (ex instanceof PhoneAlreadyExistsException phoneExists) {
            String message = messageService.getMessage(phoneExists.getMessageCode(), new Object[]{phoneExists.getPhone()});
            return AppResponse.error(AppResponse.ErrorCode.CONFLICT.getCode(), message);
        }

        log.error("Unexpected error during async request processing", ex);
        return AppResponse.error(
                AppResponse.ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                messageService.getMessage("api.error.internalServerError")
        );
    }

    private boolean isRequestTableMissing(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof BadSqlGrammarException) {
                String message = current.getMessage();
                if (message != null && message.contains("relation \"request\" does not exist")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && message.contains("relation \"request\" does not exist")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectionClosedDuringShutdown(Throwable ex) {
        String message = ex.getMessage();
        if (message != null && message.contains("Failed to obtain R2DBC Connection")) {
            return true;
        }
        Throwable cause = ex.getCause();
        return cause != null && cause.getMessage() != null && cause.getMessage().contains("connection is closed");
    }

    private Retry workerRetrySpec() {
        return Retry.backoff(workerRetryMaxAttempts, Duration.ofMillis(workerRetryBackoffMs))
                .filter(this::isTransientDbError)
                .doBeforeRetry(signal -> log.warn(
                        "Request worker transient DB error, retry {}/{}",
                        signal.totalRetries() + 1,
                        workerRetryMaxAttempts,
                        signal.failure()
                ))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isTransientDbError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && (
                    message.contains("Failed to obtain R2DBC Connection")
                            || message.contains("connection is closed")
                            || message.contains("Connection reset")
            )) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize object: {}", value, ex);
            throw new IllegalStateException("Failed to serialize object", ex);
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            log.error("Failed to deserialize JSON: {}", json, ex);
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> valueType) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON payload is empty");
        }
        try {
            return objectMapper.readValue(json, valueType);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize JSON payload", ex);
        }
    }

}

