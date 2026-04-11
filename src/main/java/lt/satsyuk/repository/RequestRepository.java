package lt.satsyuk.repository;

import lt.satsyuk.model.Request;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface RequestRepository extends R2dbcRepository<Request, UUID> {

    @Modifying
    @Query("""
            INSERT INTO request (
                id,
                type,
                status,
                created_at,
                status_changed_at,
                request_data,
                response_data
            ) VALUES (
                :id,
                :type,
                :status,
                :createdAt,
                :statusChangedAt,
                :requestData,
                :responseData
            )
            """)
    Mono<Integer> insertRequest(@Param("id") UUID id,
                                @Param("type") String type,
                                @Param("status") String status,
                                @Param("createdAt") OffsetDateTime createdAt,
                                @Param("statusChangedAt") OffsetDateTime statusChangedAt,
                                @Param("requestData") String requestData,
                                @Param("responseData") String responseData);

    @Query("""
            WITH pending AS (
                SELECT id
                FROM request
                WHERE status = 'PENDING'
                  AND type = 'CLIENT_CREATE'
                ORDER BY created_at ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE request r
               SET status = 'PROCESSING',
                   status_changed_at = :now
              FROM pending
             WHERE r.id = pending.id
            RETURNING r.*
            """)
    Flux<Request> claimPendingClientCreateBatch(@Param("batchSize") int batchSize,
                                                @Param("now") OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE request
               SET status = 'PENDING',
                   status_changed_at = :now
             WHERE status = 'PROCESSING'
               AND type = 'CLIENT_CREATE'
               AND status_changed_at < :staleBefore
            """)
    Mono<Integer> reclaimStaleClientCreateRequests(@Param("staleBefore") OffsetDateTime staleBefore,
                                                   @Param("now") OffsetDateTime now);

    @Query("""
            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (:now - status_changed_at)))::bigint, 0)
              FROM request
             WHERE status = 'PROCESSING'
               AND type = 'CLIENT_CREATE'
               AND status_changed_at < :staleBefore
            """)
    Mono<Long> findMaxStaleClientCreateAgeSeconds(@Param("staleBefore") OffsetDateTime staleBefore,
                                                  @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE request SET status = 'COMPLETED', response_data = :responseData, status_changed_at = :now WHERE id = :id AND status = 'PROCESSING'")
    Mono<Integer> markCompleted(@Param("id") UUID id,
                                @Param("responseData") String responseData,
                                @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE request SET status = 'FAILED', response_data = :responseData, status_changed_at = :now WHERE id = :id AND status = 'PROCESSING'")
    Mono<Integer> markFailed(@Param("id") UUID id,
                             @Param("responseData") String responseData,
                             @Param("now") OffsetDateTime now);
}

