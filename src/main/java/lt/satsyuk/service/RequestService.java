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
import lt.satsyuk.exception.IdempotencyKeyConflictException;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.exception.RequestNotFoundException;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.RequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
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

    public Mono<RequestAcceptedResponse> submitClientCreateRequest(CreateClientRequest createClientRequest, String authClientId) {
        UUID requestId = createClientRequest.idempotencyKey() != null
                ? createClientRequest.idempotencyKey()
                : UUID.randomUUID();
        OffsetDateTime now = now();
        Request request = Request.builder()
                .id(requestId)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PENDING)
                .createdAt(now)
                .statusChangedAt(now)
                .requestData(writeJson(createClientRequest))
                .authClientId(authClientId)
                .build();

        return requestRepository.insertRequest(
                        request.getId(),
                        request.getType().name(),
                        request.getStatus().name(),
                        request.getCreatedAt(),
                        request.getStatusChangedAt(),
                        request.getRequestData(),
                        authClientId
                )
                .flatMap(rows -> {
                    if (rows == 1) {
                        return Mono.just(new RequestAcceptedResponse(request.getId(), request.getStatus()));
                    }
                    return Mono.error(new IllegalStateException("Failed to persist async request"));
                })
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    if (createClientRequest.idempotencyKey() == null) {
                        return Mono.error(ex);
                    }
                    return requestRepository.findByIdAndAuthClientId(requestId, authClientId)
                            .switchIfEmpty(Mono.error(ex))
                            .flatMap(existing -> {
                                if (!request.getRequestData().equals(existing.getRequestData())) {
                                    return Mono.error(new IdempotencyKeyConflictException(
                                            "error.request.idempotencyKeyConflict"));
                                }
                                return Mono.just(new RequestAcceptedResponse(existing.getId(), existing.getStatus()));
                            });
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
                            } else if (isConnectionClosedDuringShutdown(ex)) {
                                log.debug("Request worker stopped because DB connection is already closed");
                            } else {
                                log.error("Request worker iteration failed", ex);
                            }
                        }
                );
    }

    public Mono<RequestStatusResponse> getRequestStatus(UUID requestId, String authClientId) {
        return requestRepository.findByIdAndAuthClientId(requestId, authClientId)
                .map(this::toStatusResponse)
                .switchIfEmpty(Mono.defer(() -> requestRepository.findById(requestId)
                        .flatMap(request -> {
                            if ("unknown".equals(request.getAuthClientId())) {
                                return Mono.just(toStatusResponse(request));
                            }
                            return Mono.error(new RequestNotFoundException(requestId));
                        })
                        .switchIfEmpty(Mono.error(new RequestNotFoundException(requestId)))));
    }

    private RequestStatusResponse toStatusResponse(Request request) {
        return new RequestStatusResponse(
                request.getId(),
                request.getType(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getStatusChangedAt(),
                readJson(request.getResponseData())
        );
    }

    public record CreateRequestResult(UUID requestId, boolean alreadyExisted, RequestStatus status, String savedResponseData) {}

    public Mono<CreateRequestResult> createPendingRequestIfAbsent(UUID idempotencyKey, Object payload,
                                                                  RequestType type, String authClientId) {
        OffsetDateTime now = now();
        String payloadJson = writeJson(payload);

        if (idempotencyKey != null) {
            return requestRepository.findByIdAndAuthClientId(idempotencyKey, authClientId)
                    .flatMap(existing -> {
                        if (existing.getType() == type && jsonEquals(existing.getRequestData(), payloadJson)) {
                            return Mono.just(new CreateRequestResult(existing.getId(), true, existing.getStatus(), existing.getResponseData()));
                        }
                        return Mono.error(new IdempotencyKeyConflictException(idempotencyKey.toString()));
                    })
                    .switchIfEmpty(Mono.defer(() -> createNewRequest(idempotencyKey, payloadJson, type, authClientId, now)));
        }

        return createNewRequest(null, payloadJson, type, authClientId, now);
    }

    private Mono<CreateRequestResult> createNewRequest(UUID idempotencyKey, String payloadJson,
                                                        RequestType type, String authClientId, OffsetDateTime now) {
        UUID requestId = idempotencyKey != null ? idempotencyKey : UUID.randomUUID();
        return requestRepository.insertRequest(
                        requestId,
                        type.name(),
                        RequestStatus.PENDING.name(),
                        now,
                        now,
                        payloadJson,
                        authClientId
                )
                .flatMap(rows -> {
                    if (rows == 1) {
                        return Mono.just(new CreateRequestResult(requestId, false, RequestStatus.PENDING, null));
                    }
                    return Mono.error(new IllegalStateException("Failed to persist request"));
                })
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    if (idempotencyKey == null) {
                        return Mono.error(ex);
                    }
                    return requestRepository.findByIdAndAuthClientId(requestId, authClientId)
                            .switchIfEmpty(Mono.error(ex))
                            .flatMap(existing -> {
                                if (existing.getType() == type && jsonEquals(existing.getRequestData(), payloadJson)) {
                                    return Mono.just(new CreateRequestResult(existing.getId(), true, existing.getStatus(), existing.getResponseData()));
                                }
                                return Mono.error(new IdempotencyKeyConflictException(String.valueOf(requestId)));
                            });
                });
    }

    public Mono<Void> completeRequest(UUID requestId, String authClientId, String responseData) {
        return requestRepository.markCompleted(requestId, authClientId, responseData, now())
                .then();
    }

    public Mono<Void> failRequest(UUID requestId, String authClientId, String errorData) {
        return requestRepository.markFailed(requestId, authClientId, errorData, now())
                .then();
    }

    boolean jsonEquals(String json1, String json2) {
        if (json1 == null && json2 == null) return true;
        if (json1 == null || json2 == null) return false;
        try {
            var tree1 = objectMapper.readTree(json1);
            var tree2 = objectMapper.readTree(json2);
            return tree1.equals(tree2);
        } catch (JsonProcessingException _) {
            return json1.equals(json2);
        }
    }

    private Mono<Void> claimAndProcessBatch() {
        OffsetDateTime claimedAt = now();
        AtomicInteger claimedCount = new AtomicInteger();
        int normalizedBatchSize = resolveWorkerBatchSize();
        int maxConcurrency = resolveWorkerMaxConcurrency(normalizedBatchSize);
        return reclaimStaleProcessingRequests(claimedAt)
                .thenMany(requestRepository.claimPendingClientCreateBatch(normalizedBatchSize, claimedAt))
                .doOnNext(request -> {
                    claimedCount.incrementAndGet();
                    recordClaimLag(claimedAt, request);
                })
                .flatMapSequential(this::processClaimedRequest, maxConcurrency, 1)
                .then(Mono.fromRunnable(() -> claimBatchSize.record(claimedCount.get())));
    }

    private int resolveWorkerBatchSize() {
        if (workerBatchSize < 1) {
            log.warn("Invalid app.request.worker.batch-size={} configured; using 1", workerBatchSize);
            return 1;
        }
        return workerBatchSize;
    }

    private int resolveWorkerMaxConcurrency(int normalizedBatchSize) {
        if (workerMaxConcurrency < 1) {
            log.warn("Invalid app.request.worker.max-concurrency={} configured; using 1", workerMaxConcurrency);
            return 1;
        }
        if (workerMaxConcurrency > normalizedBatchSize) {
            log.warn(
                    "app.request.worker.max-concurrency={} exceeds effective batch-size={}; using {}",
                    workerMaxConcurrency,
                    normalizedBatchSize,
                    normalizedBatchSize
            );
            return normalizedBatchSize;
        }
        return workerMaxConcurrency;
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
        String authClientId = request.getAuthClientId();
        long startedNanos = System.nanoTime();
        return Mono.defer(() -> {
                    if (request.getType() != RequestType.CLIENT_CREATE) {
                        return Mono.error(new IllegalStateException("Unsupported request type: " + request.getType()));
                    }

                    CreateClientRequest payload = readJson(request.getRequestData(), CreateClientRequest.class);
                    return clientService.create(payload, authClientId)
                            .flatMap(clientResponse -> markCompleted(requestId, authClientId, clientResponse, startedNanos));
                })
                .onErrorResume(ex -> markFailed(requestId, authClientId, ex, startedNanos));
    }

    private Mono<Void> markCompleted(UUID requestId, String authClientId, ClientResponse clientResponse, long startedNanos) {
        String responseJson = writeJson(AppResponse.ok(clientResponse));
        return requestRepository.markCompleted(requestId, authClientId, responseJson, now())
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


    private Mono<Void> markFailed(UUID requestId, String authClientId, Throwable ex, long startedNanos) {
        AppResponse<Void> errorPayload = toWorkerError(ex);
        String errorJson = writeJson(errorPayload);
        return requestRepository.markFailed(requestId, authClientId, errorJson, now())
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

