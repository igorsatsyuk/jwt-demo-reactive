package lt.satsyuk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("request")
public class Request {

    @Id
    private UUID id;

    private RequestType type;

    private RequestStatus status;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("status_changed_at")
    private OffsetDateTime statusChangedAt;

    @Column("request_data")
    private String requestData;

    @Column("response_data")
    private String responseData;
}

