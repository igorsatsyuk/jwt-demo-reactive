package lt.satsyuk.controller;

import lt.satsyuk.dto.AppResponse;
import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.dto.RequestAcceptedResponse;
import lt.satsyuk.model.RequestStatus;
import lt.satsyuk.service.ClientService;
import lt.satsyuk.service.RequestService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientControllerTest {

    private final ClientService clientService = mock(ClientService.class);
    private final RequestService requestService = mock(RequestService.class);

    private final ClientController controller = new ClientController(clientService, requestService);

    @Test
    void create_returnsAcceptedEnvelope() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000000");
        RequestAcceptedResponse accepted = new RequestAcceptedResponse(UUID.randomUUID(), RequestStatus.PENDING);
        when(requestService.submitClientCreateRequest(request)).thenReturn(Mono.just(accepted));

        StepVerifier.create(controller.create(request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                    assertThat(response.getBody()).isEqualTo(AppResponse.ok(accepted));
                })
                .verifyComplete();
    }

    @Test
    void get_wrapsClientResponseIntoAppResponse() {
        ClientResponse client = new ClientResponse(10L, "Jane", "Doe", "+37060000001");
        when(clientService.get(10L)).thenReturn(Mono.just(client));

        StepVerifier.create(controller.get(10L))
                .expectNext(AppResponse.ok(client))
                .verifyComplete();
    }

    @Test
    void search_wrapsListIntoAppResponse() {
        List<ClientResponse> result = List.of(
                new ClientResponse(1L, "Alice", "Smith", "+37060000002"),
                new ClientResponse(2L, "Bob", "Smith", "+37060000003")
        );
        when(clientService.searchByNameOrSurname("Smith")).thenReturn(Mono.just(result));

        StepVerifier.create(controller.search("Smith"))
                .expectNext(AppResponse.ok(result))
                .verifyComplete();
    }
}

