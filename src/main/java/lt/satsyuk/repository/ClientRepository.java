package lt.satsyuk.repository;

import lt.satsyuk.model.Client;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClientRepository extends R2dbcRepository<Client, Long> {

    Mono<Boolean> existsByPhone(String phone);

    @Query("""
            SELECT *
              FROM client
             WHERE lower(first_name) LIKE '%' || lower(:query) || '%'
                OR lower(last_name) LIKE '%' || lower(:query) || '%'
             ORDER BY id ASC
             LIMIT :limit
            """)
    Flux<Client> searchByNameOrSurname(@Param("query") String query, @Param("limit") int limit);
}


