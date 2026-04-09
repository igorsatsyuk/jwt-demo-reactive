package lt.satsyuk.repository;

import lt.satsyuk.model.Account;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface AccountRepository extends R2dbcRepository<Account, Long> {

    Mono<Account> findByClientId(Long clientId);

    @Query("SELECT * FROM account WHERE client_id = :clientId FOR UPDATE")
    Mono<Account> findByClientIdForPessimisticUpdate(@Param("clientId") Long clientId);
}

