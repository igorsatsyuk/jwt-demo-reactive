package lt.satsyuk.repository;

import lt.satsyuk.model.Client;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClientRepository extends R2dbcRepository<Client, Long> {

    Mono<Boolean> existsByPhone(String phone);

    Mono<Client> findByPhone(String phone);

    @Query("""
            SELECT c.*
              FROM client c
              JOIN client_access ca ON ca.client_id = c.id
             WHERE c.id = :id
               AND ca.auth_client_id = :authClientId
            """)
    Mono<Client> findByIdAndAuthClientId(@Param("id") Long id, @Param("authClientId") String authClientId);

    @Query("""
            SELECT c.*
              FROM client c
              JOIN client_access ca ON ca.client_id = c.id
             WHERE ca.auth_client_id = :authClientId
               AND (lower(c.first_name) LIKE '%' || lower(:query) || '%'
                    OR lower(c.last_name) LIKE '%' || lower(:query) || '%')
             ORDER BY c.id ASC
             LIMIT :limit
            """)
    Flux<Client> searchByNameOrSurnameAndAuthClientId(
            @Param("query") String query,
            @Param("authClientId") String authClientId,
            @Param("limit") int limit);
}


