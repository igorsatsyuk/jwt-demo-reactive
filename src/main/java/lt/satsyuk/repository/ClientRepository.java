package lt.satsyuk.repository;

import lt.satsyuk.model.Client;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClientRepository extends R2dbcRepository<Client, Long> {

    Mono<Boolean> existsByPhone(String phone);

    Flux<Client> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCaseOrderByIdAsc(
            String firstName,
            String lastName
    );
}


