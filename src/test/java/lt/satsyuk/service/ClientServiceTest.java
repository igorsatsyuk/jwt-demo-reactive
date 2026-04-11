package lt.satsyuk.service;

import lt.satsyuk.dto.CreateClientRequest;
import lt.satsyuk.exception.PhoneAlreadyExistsException;
import lt.satsyuk.mapper.ClientMapper;
import lt.satsyuk.repository.AccountRepository;
import lt.satsyuk.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
                    org.assertj.core.api.Assertions.assertThat(error).isInstanceOf(PhoneAlreadyExistsException.class);
                    PhoneAlreadyExistsException ex = (PhoneAlreadyExistsException) error;
                    org.assertj.core.api.Assertions.assertThat(ex.getPhone()).isEqualTo(request.phone());
                })
                .verify();

        verify(clientRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }
}

