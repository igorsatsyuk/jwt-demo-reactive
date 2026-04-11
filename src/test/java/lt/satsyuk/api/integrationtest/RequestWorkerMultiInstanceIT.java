package lt.satsyuk.api.integrationtest;

import lt.satsyuk.MainApplication;
import lt.satsyuk.dto.RequestAcceptedResponse;
import lt.satsyuk.model.Request;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import lt.satsyuk.repository.RequestRepository;
import lt.satsyuk.service.RequestService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "app.request.worker.interval-ms=600000",
        "app.request.worker.initial-delay-ms=600000",
        "app.request.worker.batch-size=1",
        "app.request.worker.processing-timeout=5s"
})
class RequestWorkerMultiInstanceIT extends AbstractIntegrationTest {

    @Autowired
    private RequestService requestService;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DatabaseClient databaseClient;

    private ConfigurableApplicationContext secondInstance;

    @BeforeEach
    void setUp() {
        requestRepository.deleteAll()
                .then(accountRepository.deleteAll())
                .then(clientRepository.deleteAll())
                .block();

        secondInstance = new SpringApplicationBuilder(MainApplication.class)
                .run(
                        "--spring.profiles.active=test",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.r2dbc.url=r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/appdb",
                        "--spring.r2dbc.username=" + postgres.getUsername(),
                        "--spring.r2dbc.password=" + postgres.getPassword(),
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.flyway.url=" + postgres.getJdbcUrl(),
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--app.request.worker.interval-ms=600000",
                        "--app.request.worker.initial-delay-ms=600000",
                        "--app.request.worker.batch-size=1",
                        "--app.request.worker.processing-timeout=5s"
                );
    }

    @AfterEach
    void tearDown() {
        if (secondInstance != null) {
            secondInstance.close();
        }
    }

    @Test
    void two_instances_do_not_duplicate_single_request_processing() throws Exception {
        String phone = "+37069997777";
        RequestAcceptedResponse accepted = requestService
                .submitClientCreateRequest(new lt.satsyuk.dto.CreateClientRequest("Multi", "Instance", phone))
                .block();

        assertThat(accepted).isNotNull();

        RequestService secondRequestService = secondInstance.getBean(RequestService.class);
        CountDownLatch startLatch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                awaitStart(startLatch);
                requestService.processPendingRequests();
            });
            executor.submit(() -> {
                awaitStart(startLatch);
                secondRequestService.processPendingRequests();
            });
            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Request request = requestRepository.findById(accepted.requestId()).block();
                    assertThat(request).isNotNull();
                    assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);

                    Long clientCountByPhone = databaseClient.sql("SELECT COUNT(*) FROM client WHERE phone = :phone")
                            .bind("phone", phone)
                            .mapValue(Long.class)
                            .one()
                            .block();
                    assertThat(clientCountByPhone).isEqualTo(1L);
                });
    }

    @Test
    void second_instance_reclaims_stale_processing_after_first_instance_crash_simulation() {
        String phone = "+37069998888";
        RequestAcceptedResponse accepted = requestService
                .submitClientCreateRequest(new lt.satsyuk.dto.CreateClientRequest("Crash", "Reclaim", phone))
                .block();

        assertThat(accepted).isNotNull();
        UUID requestId = accepted.requestId();

        Request claimed = requestRepository
                .claimPendingClientCreateBatch(1, OffsetDateTime.now(ZoneOffset.UTC))
                .next()
                .block();

        assertThat(claimed).isNotNull();
        assertThat(claimed.getId()).isEqualTo(requestId);
        assertThat(claimed.getStatus()).isEqualTo(RequestStatus.PROCESSING);

        OffsetDateTime staleAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        Long updated = databaseClient.sql("""
                        UPDATE request
                           SET status_changed_at = :staleAt
                         WHERE id = :id
                        """)
                .bind("staleAt", staleAt)
                .bind("id", requestId)
                .fetch()
                .rowsUpdated()
                .block();
        assertThat(updated).isEqualTo(1L);

        RequestService secondRequestService = secondInstance.getBean(RequestService.class);
        secondRequestService.processPendingRequests();

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Request request = requestRepository.findById(requestId).block();
                    assertThat(request).isNotNull();
                    assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);

                    Long clientCountByPhone = databaseClient.sql("SELECT COUNT(*) FROM client WHERE phone = :phone")
                            .bind("phone", phone)
                            .mapValue(Long.class)
                            .one()
                            .block();
                    assertThat(clientCountByPhone).isEqualTo(1L);
                });
    }

    private void awaitStart(CountDownLatch latch) {
        try {
            boolean started = latch.await(2, TimeUnit.SECONDS);
            if (!started) {
                throw new IllegalStateException("Timed out while waiting to start worker trigger");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker trigger thread interrupted", ex);
        }
    }
}

