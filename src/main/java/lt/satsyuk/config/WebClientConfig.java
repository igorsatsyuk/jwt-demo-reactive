package lt.satsyuk.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(ObservationRegistry observationRegistry, KeycloakProperties keycloakProperties) {
        Duration connectTimeout = keycloakProperties.getConnectTimeout();
        Duration readTimeout = keycloakProperties.getReadTimeout();

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(readTimeout)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(connectTimeout.toMillis()));

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        return WebClient.builder()
                .clientConnector(connector)
                .observationRegistry(observationRegistry)
                .build();
    }
}

