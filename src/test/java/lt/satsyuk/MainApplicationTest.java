package lt.satsyuk;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MainApplicationTest {

    @Test
    void main_delegatesToSpringApplicationRun() {
        String[] args = new String[]{"--spring.main.web-application-type=none"};

        try (MockedStatic<SpringApplication> springApplication = Mockito.mockStatic(SpringApplication.class)) {
            springApplication.when(() -> SpringApplication.run(MainApplication.class, args)).thenReturn(null);

            MainApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(MainApplication.class, args));
        }
    }

    @Test
    void class_hasRequiredEnablingAnnotations() {
        assertThat(MainApplication.class.isAnnotationPresent(EnableCaching.class)).isTrue();
        assertThat(MainApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }

    @Test
    void defaultConstructor_isInstantiable() {
        assertThatCode(() -> MainApplication.class.getDeclaredConstructor().newInstance())
                .doesNotThrowAnyException();
    }
}
