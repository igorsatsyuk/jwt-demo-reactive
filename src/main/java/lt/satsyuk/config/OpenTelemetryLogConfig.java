package lt.satsyuk.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the Logback {@link OpenTelemetryAppender} to the Spring Boot-managed
 * OpenTelemetry SDK instance.
 *
 * <p>Spring Boot 4's {@code OtlpLoggingAutoConfiguration} creates the
 * {@code SdkLoggerProvider} and the OTLP log exporter, but it does NOT
 * automatically install the Logback appender. Without this call the appender
 * receives Logback events but uses {@code OpenTelemetry.noop()}, silently
 * dropping every log record before it reaches the exporter pipeline.</p>
 *
 * <p>The {@code OpenTelemetry} bean is provided by
 * {@code OpenTelemetrySdkAutoConfiguration} (part of
 * {@code spring-boot-starter-opentelemetry}).</p>
 */
@Configuration
@ConditionalOnClass(OpenTelemetryAppender.class)
public class OpenTelemetryLogConfig {

    public OpenTelemetryLogConfig(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}

