package lt.satsyuk.repository;

import lt.satsyuk.model.ClientAccess;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface ClientAccessRepository extends R2dbcRepository<ClientAccess, Long> {

    @Query("SELECT EXISTS(SELECT 1 FROM client_access WHERE client_id = :clientId AND auth_client_id = :authClientId)")
    Mono<Boolean> existsByClientIdAndAuthClientId(@Param("clientId") Long clientId, @Param("authClientId") String authClientId);
}
