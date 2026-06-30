package lt.satsyuk.service;

import lt.satsyuk.dto.ClientResponse;
import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.ClientNotFoundException;
import lt.satsyuk.exception.ClientSearchQueryTooShortException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.model.Account;
import lt.satsyuk.model.Client;
import lt.satsyuk.model.ClientAccess;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientAccessRepository;
import lt.satsyuk.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientServiceTest {

    protected static final String JOHN = "John";
    protected static final String DOE = "Doe";
    protected static final String JANE = "Jane";
    protected static final String SMITH = "Smith";
    protected static final String ANNA = "Anna";
    protected static final String BOB = "Bob";
    private static final String AUTH_CLIENT_ID = "spring-app";

    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final ClientAccessRepository clientAccessRepository = mock(ClientAccessRepository.class);
    private final ClientMapper clientMapper = mock(ClientMapper.class);

    private final ClientService clientService = new ClientService(clientRepository, accountRepository, clientAccessRepository, clientMapper);

    @Test
    void create_returnsExistingClientAndAddsAccessWhenPhoneExists() {
        CreateClientRequest request = new CreateClientRequest(JOHN, DOE, "+37060000001", null);
        Client existing = Client.builder().id(7L).firstName(JOHN).lastName(DOE).phone("+37060000001").build();
        ClientResponse response = new ClientResponse(7L, JOHN, DOE, "+37060000001");

        when(clientRepository.findByPhone(request.phone())).thenReturn(Mono.just(existing));
        when(clientAccessRepository.existsByClientIdAndAuthClientId(7L, AUTH_CLIENT_ID)).thenReturn(Mono.just(false));
        when(clientAccessRepository.save(any(ClientAccess.class))).thenReturn(Mono.just(new ClientAccess()));
        when(clientMapper.toResponse(existing)).thenReturn(response);

        StepVerifier.create(clientService.create(request, AUTH_CLIENT_ID))
                .expectNext(response)
                .verifyComplete();

        verify(clientAccessRepository).save(any(ClientAccess.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void create_doesNotDuplicateAccessWhenPhoneExistsAndAccessAlreadyPresent() {
        CreateClientRequest request = new CreateClientRequest(JOHN, DOE, "+37060000001", null);
        Client existing = Client.builder().id(7L).firstName(JOHN).lastName(DOE).phone("+37060000001").build();
        ClientResponse response = new ClientResponse(7L, JOHN, DOE, "+37060000001");

        when(clientRepository.findByPhone(request.phone())).thenReturn(Mono.just(existing));
        when(clientAccessRepository.existsByClientIdAndAuthClientId(7L, AUTH_CLIENT_ID)).thenReturn(Mono.just(true));
        when(clientMapper.toResponse(existing)).thenReturn(response);

        StepVerifier.create(clientService.create(request, AUTH_CLIENT_ID))
                .expectNext(response)
                .verifyComplete();

        verify(clientAccessRepository, never()).save(any(ClientAccess.class));
    }

    @Test
    void create_persistsClientAndAccount_thenReturnsMappedResponse() {
        CreateClientRequest request = new CreateClientRequest(JOHN, DOE, "+37060000001", null);
        Client mappedClient = Client.builder().firstName(JOHN).lastName(DOE).phone(request.phone()).build();
        Client savedClient = Client.builder().id(11L).firstName(JOHN).lastName(DOE).phone(request.phone()).build();
        Account savedAccount = Account.builder().id(20L).clientId(11L).balance(BigDecimal.ZERO).build();
        ClientResponse response = new ClientResponse(11L, JOHN, DOE, request.phone());

        when(clientRepository.findByPhone(request.phone())).thenReturn(Mono.empty());
        when(clientMapper.toEntity(request)).thenReturn(mappedClient);
        when(clientRepository.save(mappedClient)).thenReturn(Mono.just(savedClient));
        when(clientAccessRepository.save(any(ClientAccess.class))).thenReturn(Mono.just(new ClientAccess()));
        when(accountRepository.save(any(Account.class))).thenReturn(Mono.just(savedAccount));
        when(clientMapper.toResponse(savedClient)).thenReturn(response);

        StepVerifier.create(clientService.create(request, AUTH_CLIENT_ID))
                .expectNext(response)
                .verifyComplete();

        verify(clientAccessRepository).save(any(ClientAccess.class));
    }

    @Test
    void create_handlesDuplicateKeyByLookingUpExistingClient() {
        CreateClientRequest request = new CreateClientRequest(JOHN, DOE, "+37060000001", null);
        Client mappedClient = Client.builder().firstName(JOHN).lastName(DOE).phone(request.phone()).build();
        Client existing = Client.builder().id(7L).firstName(JOHN).lastName(DOE).phone("+37060000001").build();
        ClientResponse response = new ClientResponse(7L, JOHN, DOE, "+37060000001");

        when(clientRepository.findByPhone(request.phone()))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(existing));
        when(clientMapper.toEntity(request)).thenReturn(mappedClient);
        when(clientRepository.save(mappedClient)).thenReturn(Mono.error(new DuplicateKeyException("uq_client_phone")));
        when(clientAccessRepository.existsByClientIdAndAuthClientId(7L, AUTH_CLIENT_ID)).thenReturn(Mono.just(false));
        when(clientAccessRepository.save(any(ClientAccess.class))).thenReturn(Mono.just(new ClientAccess()));
        when(clientMapper.toResponse(existing)).thenReturn(response);

        StepVerifier.create(clientService.create(request, AUTH_CLIENT_ID))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void create_emitsMapperFailureReactively() {
        CreateClientRequest request = new CreateClientRequest(JOHN, DOE, "+37060000010", null);
        IllegalStateException mapperFailure = new IllegalStateException("mapper failed");
        when(clientRepository.findByPhone(request.phone())).thenReturn(Mono.empty());
        when(clientMapper.toEntity(request)).thenThrow(mapperFailure);

        StepVerifier.create(clientService.create(request, AUTH_CLIENT_ID))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error).isSameAs(mapperFailure);
                })
                .verify();

        verify(clientRepository, never()).save(any());
        verifyNoInteractions(accountRepository);
    }

    @Test
    void get_returnsClientWhenFound() {
        Client client = Client.builder().id(7L).firstName(JANE).lastName(DOE).phone("+37060000003").build();
        ClientResponse response = new ClientResponse(7L, JANE, DOE, "+37060000003");

        when(clientRepository.findByIdAndAuthClientId(7L, AUTH_CLIENT_ID)).thenReturn(Mono.just(client));
        when(clientMapper.toResponse(client)).thenReturn(response);

        StepVerifier.create(clientService.get(7L, AUTH_CLIENT_ID))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void get_returnsNotFoundWhenMissing() {
        when(clientRepository.findByIdAndAuthClientId(100L, AUTH_CLIENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(clientService.get(100L, AUTH_CLIENT_ID))
                .expectError(ClientNotFoundException.class)
                .verify();
    }

    @Test
    void search_rejectsTooShortQueryAfterTrim() {
        StepVerifier.create(clientService.searchByNameOrSurname("  ab  ", AUTH_CLIENT_ID))
                .expectError(ClientSearchQueryTooShortException.class)
                .verify();
    }

    @Test
    void search_trimsQueryAndAppliesMaxResultsLimit() {
        ReflectionTestUtils.setField(clientService, "searchMaxResults", 2);

        Client c1 = Client.builder().id(1L).firstName(ANNA).lastName(SMITH).phone("+37060000004").build();
        Client c2 = Client.builder().id(2L).firstName(BOB).lastName(SMITH).phone("+37060000005").build();

        ClientResponse r1 = new ClientResponse(1L, ANNA, SMITH, "+37060000004");
        ClientResponse r2 = new ClientResponse(2L, BOB, SMITH, "+37060000005");

        when(clientRepository.searchByNameOrSurnameAndAuthClientId(SMITH, AUTH_CLIENT_ID, 2)).thenReturn(Flux.just(c1, c2));
        when(clientMapper.toResponse(c1)).thenReturn(r1);
        when(clientMapper.toResponse(c2)).thenReturn(r2);

        StepVerifier.create(clientService.searchByNameOrSurname("  Smith  ", AUTH_CLIENT_ID))
                .assertNext(result -> assertThat(result).isEqualTo(List.of(r1, r2)))
                .verifyComplete();
    }
}
