package lt.satsyuk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.repository.RequestRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestServiceTest {

    private final RequestRepository requestRepository = mock(RequestRepository.class);
    private final ClientService clientService = mock(ClientService.class);
    private final MessageService messageService = mock(MessageService.class);

    private final RequestService requestService = new RequestService(
            requestRepository,
            clientService,
            new ObjectMapper(),
            messageService,
            new SimpleMeterRegistry()
    );

    @Test
    void submitClientCreateRequest_failsWhenInsertDidNotPersistExactlyOneRow() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000001");

        when(requestRepository.insertRequest(any(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Mono.just(0));

        StepVerifier.create(requestService.submitClientCreateRequest(request))
                .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Failed to persist async request"))
                .verify();
    }
}

