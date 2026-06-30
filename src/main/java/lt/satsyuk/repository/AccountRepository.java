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

    @Query("""
            SELECT a.*
              FROM account a
              JOIN client c ON c.id = a.client_id
              JOIN client_access ca ON ca.client_id = c.id
             WHERE a.client_id = :clientId
               AND ca.auth_client_id = :authClientId
            """)
    Mono<Account> findByClientIdAndAuthClientId(@Param("clientId") Long clientId, @Param("authClientId") String authClientId);

    @Query("""
            SELECT a.*
              FROM account a
              JOIN client c ON c.id = a.client_id
              JOIN client_access ca ON ca.client_id = c.id
             WHERE a.client_id = :clientId
               AND ca.auth_client_id = :authClientId
             FOR UPDATE
            """)
    Mono<Account> findByClientIdAndAuthClientIdForPessimisticUpdate(@Param("clientId") Long clientId, @Param("authClientId") String authClientId);
}

