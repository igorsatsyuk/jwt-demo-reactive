package lt.satsyuk.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void clock_returnsUtcSystemClock() {
        Clock clock = securityConfig.clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
