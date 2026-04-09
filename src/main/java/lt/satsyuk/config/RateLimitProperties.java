package lt.satsyuk.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    @Valid
    @NotEmpty
    private List<Rule> rules = new ArrayList<>();

    @Getter
    @Setter
    public static class Rule {

        @NotBlank
        private String id;

        private boolean enabled = true;

        @Min(0)
        private int order;

        @NotBlank
        private String pathPattern;

        private Set<String> methods = new LinkedHashSet<>();

        private Set<String> clientIds = new LinkedHashSet<>();

        @NotNull
        private KeyStrategy keyStrategy = KeyStrategy.IP;

        private String keyHeader;

        @NotBlank
        private String cacheName = "rateLimitBuckets";

        @Min(1)
        private long capacity;

        @Min(1)
        private long windowSeconds;

        @AssertTrue(message = "key-header is required when key-strategy=HEADER")
        public boolean isHeaderValid() {
            return keyStrategy != KeyStrategy.HEADER || StringUtils.hasText(keyHeader);
        }
    }

    public enum KeyStrategy {
        IP,
        CLIENT_ID,
        USERNAME,
        HEADER,
        CLIENT_ID_AND_IP
    }
}

