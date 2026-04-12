package lt.satsyuk.service;

import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.ClientNotFoundException;
import lt.satsyuk.exception.ClientSearchQueryTooShortException;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientServiceTest {

    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final ClientMapper clientMapper = mock(ClientMapper.class);

    private final ClientService clientService = new ClientService(clientRepository, accountRepository, clientMapper);

    @Test
    void create_returnsConflictWhenPhoneAlreadyExists() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000000");
        when(clientRepository.existsByPhone(request.phone())).thenReturn(Mono.just(true));

        StepVerifier.create(clientService.create(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(PhoneAlreadyExistsException.class);
                    PhoneAlreadyExistsException ex = (PhoneAlreadyExistsException) error;
                    assertThat(ex.getPhone()).isEqualTo(request.phone());
                })
                .verify();

        verify(clientRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void create_persistsClientAndAccount_thenReturnsMappedResponse() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000001");
        Client mappedClient = Client.builder().firstName("John").lastName("Doe").phone(request.phone()).build();
        Client savedClient = Client.builder().id(11L).firstName("John").lastName("Doe").phone(request.phone()).build();
        Account savedAccount = Account.builder().id(20L).clientId(11L).balance(BigDecimal.ZERO).build();
        ClientResponse response = new ClientResponse(11L, "John", "Doe", request.phone());

        when(clientRepository.existsByPhone(request.phone())).thenReturn(Mono.just(false));
        when(clientMapper.toEntity(request)).thenReturn(mappedClient);
        when(clientRepository.save(mappedClient)).thenReturn(Mono.just(savedClient));
        when(accountRepository.save(any(Account.class))).thenReturn(Mono.just(savedAccount));
        when(clientMapper.toResponse(savedClient)).thenReturn(response);

        StepVerifier.create(clientService.create(request))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void create_mapsDataIntegrityViolationToPhoneAlreadyExists() {
        CreateClientRequest request = new CreateClientRequest("John", "Doe", "+37060000002");
        Client mappedClient = Client.builder().firstName("John").lastName("Doe").phone(request.phone()).build();

        when(clientRepository.existsByPhone(request.phone())).thenReturn(Mono.just(false));
        when(clientMapper.toEntity(request)).thenReturn(mappedClient);
        when(clientRepository.save(mappedClient)).thenReturn(Mono.error(new DataIntegrityViolationException("dup")));

        StepVerifier.create(clientService.create(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(PhoneAlreadyExistsException.class);
                    assertThat(((PhoneAlreadyExistsException) error).getPhone()).isEqualTo(request.phone());
                })
                .verify();

        verify(accountRepository, never()).save(any());
    }

    @Test
    void get_returnsClientWhenFound() {
        Client client = Client.builder().id(7L).firstName("Jane").lastName("Doe").phone("+37060000003").build();
        ClientResponse response = new ClientResponse(7L, "Jane", "Doe", "+37060000003");

        when(clientRepository.findById(7L)).thenReturn(Mono.just(client));
        when(clientMapper.toResponse(client)).thenReturn(response);

        StepVerifier.create(clientService.get(7L))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void get_returnsNotFoundWhenMissing() {
        when(clientRepository.findById(100L)).thenReturn(Mono.empty());

        StepVerifier.create(clientService.get(100L))
                .expectError(ClientNotFoundException.class)
                .verify();
    }

    @Test
    void search_rejectsTooShortQueryAfterTrim() {
        StepVerifier.create(clientService.searchByNameOrSurname("  ab  "))
                .expectError(ClientSearchQueryTooShortException.class)
                .verify();
    }

    @Test
    void search_trimsQueryAndAppliesMaxResultsLimit() {
        ReflectionTestUtils.setField(clientService, "searchMaxResults", 2);

        Client c1 = Client.builder().id(1L).firstName("Anna").lastName("Smith").phone("+37060000004").build();
        Client c2 = Client.builder().id(2L).firstName("Bob").lastName("Smith").phone("+37060000005").build();
        Client c3 = Client.builder().id(3L).firstName("Carl").lastName("Smith").phone("+37060000006").build();

        ClientResponse r1 = new ClientResponse(1L, "Anna", "Smith", "+37060000004");
        ClientResponse r2 = new ClientResponse(2L, "Bob", "Smith", "+37060000005");
        ClientResponse r3 = new ClientResponse(3L, "Carl", "Smith", "+37060000006");

        when(clientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCaseOrderByIdAsc("Smith", "Smith"))
                .thenReturn(Flux.just(c1, c2, c3));
        when(clientMapper.toResponse(c1)).thenReturn(r1);
        when(clientMapper.toResponse(c2)).thenReturn(r2);
        when(clientMapper.toResponse(c3)).thenReturn(r3);

        StepVerifier.create(clientService.searchByNameOrSurname("  Smith  "))
                .assertNext(result -> assertThat(result).isEqualTo(List.of(r1, r2)))
                .verifyComplete();
    }
}
