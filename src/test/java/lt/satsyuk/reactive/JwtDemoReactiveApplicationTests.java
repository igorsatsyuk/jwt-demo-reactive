package lt.satsyuk.reactive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Placeholder smoke test requires local infrastructure; functional coverage is provided by integration tests")
@SpringBootTest(properties = {
		"spring.flyway.enabled=false",
		"management.health.r2dbc.enabled=false",
		"spring.r2dbc.url=r2dbc:postgresql://localhost:5432/appdb",
		"spring.r2dbc.username=app",
		"spring.r2dbc.password=app"
})
class JwtDemoReactiveApplicationTests {

	@Test
	void contextLoads() {
	}

}
