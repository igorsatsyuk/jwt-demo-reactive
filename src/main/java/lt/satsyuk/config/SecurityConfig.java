package lt.satsyuk.config;

import lt.satsyuk.auth.JsonAccessDeniedHandler;
import lt.satsyuk.auth.JsonAuthEntryPoint;
import lt.satsyuk.security.DpopAwareServerBearerTokenAuthenticationConverter;
import lt.satsyuk.security.KeycloakOpaqueRoleConverter;
import lt.satsyuk.security.KeycloakReactiveOpaqueTokenIntrospector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableReactiveMethodSecurity
@EnableConfigurationProperties({RateLimitProperties.class, DpopProperties.class})
public class SecurityConfig {

    @Bean
    @SuppressWarnings("java:S4502")
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveOpaqueTokenIntrospector opaqueTokenIntrospector,
                                                         DpopAwareServerBearerTokenAuthenticationConverter dpopAwareBearerTokenConverter,
                                                         JsonAuthEntryPoint jsonAuthEntryPoint,
                                                         JsonAccessDeniedHandler jsonAccessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(dpopAwareBearerTokenConverter)
                        .opaqueToken(opaqueToken -> opaqueToken.introspector(opaqueTokenIntrospector))
                        .authenticationEntryPoint(jsonAuthEntryPoint)
                        .accessDeniedHandler(jsonAccessDeniedHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jsonAuthEntryPoint)
                        .accessDeniedHandler(jsonAccessDeniedHandler)
                )
                .build();
    }

    @Bean
    public ReactiveOpaqueTokenIntrospector reactiveOpaqueTokenIntrospector(KeycloakProperties props,
                                                                           KeycloakOpaqueRoleConverter roleConverter) {
        return new KeycloakReactiveOpaqueTokenIntrospector(
                props.getIntrospectionUrl(),
                props.getResourceClientId(),
                props.getResourceClientSecret(),
                roleConverter
        );
    }

    @Bean
    public DpopAwareServerBearerTokenAuthenticationConverter dpopAwareServerBearerTokenAuthenticationConverter() {
        return new DpopAwareServerBearerTokenAuthenticationConverter();
    }
}
