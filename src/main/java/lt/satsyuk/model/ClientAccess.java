package lt.satsyuk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("client_access")
public class ClientAccess {

    @Id
    private Long id;

    @Column("client_id")
    private Long clientId;

    @Column("auth_client_id")
    private String authClientId;
}
