package lt.satsyuk.api.integrationtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.model.RequestType;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import lt.satsyuk.repository.RequestRepository;
import lt.satsyuk.service.RequestService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "app.request.worker.interval-ms=600000",
        "app.request.worker.initial-delay-ms=600000",
        "app.request.worker.batch-size=1",
        "app.request.worker.processing-timeout=1s"
})
class RequestWorkerReclaimIT extends AbstractIntegrationTest {

    private final RequestService requestService;
    private final RequestRepository requestRepository;
    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    RequestWorkerReclaimIT(
            RequestService requestService,
            RequestRepository requestRepository,
            ClientRepository clientRepository,
            AccountRepository accountRepository,
            ObjectMapper objectMapper
    ) {
        this.requestService = requestService;
        this.requestRepository = requestRepository;
        this.clientRepository = clientRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        requestRepository.deleteAll()
                .then(accountRepository.deleteAll())
                .then(clientRepository.deleteAll())
                .block();
    }

    @Test
    void worker_reclaims_stale_processing_request_and_completes_it() throws Exception {
        UUID requestId = UUID.randomUUID();
        CreateClientRequest payload = new CreateClientRequest("Stale", "Worker", "+37069995555");
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(20);
        OffsetDateTime staleProcessingAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10);

        Request request = Request.builder()
                .id(requestId)
                .type(RequestType.CLIENT_CREATE)
                .status(RequestStatus.PROCESSING)
                .createdAt(createdAt)
                .statusChangedAt(staleProcessingAt)
                .requestData(objectMapper.writeValueAsString(payload))
                .build();

        requestRepository.insertRequest(
                        request.getId(),
                        request.getType().name(),
                        request.getStatus().name(),
                        request.getCreatedAt(),
                        request.getStatusChangedAt(),
                        request.getRequestData(),
                        null
                )
                .block();

        requestService.processPendingRequests();

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Request persisted = requestRepository.findById(requestId).block();
                    assertThat(persisted).isNotNull();
                    assertThat(persisted.getStatus()).isEqualTo(RequestStatus.COMPLETED);
                    assertThat(persisted.getResponseData()).isNotBlank();
                    assertThat(clientRepository.existsByPhone(payload.phone()).block()).isTrue();
                });
    }
}

