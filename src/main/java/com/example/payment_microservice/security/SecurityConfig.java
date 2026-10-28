package com.example.payment_microservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;
    private final AllowedClients allowedClients;

    @Value("${cors.allowedMethods}")
    private String allowedMethods;

    @Value("${cors.allowedHeaders}")
    private String allowedHeaders;

    @Value("${cors.corsConfiguration}")
    private String corsConfiguration;

    /**
     * Audit P14 (2026-10-26): the origins allowed to call the service from a browser, comma separated. None by
     * default: YoSales calls it from its server and the coach only comes back by the return links (no CORS). The
     * mapping without origins used to allow every origin.
     */
    @Value("${PAYMENT_CORS_ORIGINS:}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            public void addCorsMappings(final CorsRegistry registry) {
                String[] origins = java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                        .filter(o -> !o.isEmpty()).toArray(String[]::new);
                if (origins.length == 0) {
                    return;
                }
                registry.addMapping(corsConfiguration)
                        .allowedOrigins(origins)
                        .allowedHeaders(allowedHeaders.split(","))
                        .allowedMethods(allowedMethods.split(","));
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults()) // ✅ Enables CORS support
                .csrf().disable()
                .sessionManagement()
                .sessionCreationPolicy(STATELESS)
                .and()
                .oauth2ResourceServer()
                .jwt()
                .jwtAuthenticationConverter(jwtAuthConverter);

        // Audit P14 (2026-10-26): what was left from a template is gone: the browser login (oauth2Login) with its logout
        // to localhost, and the public routes of pages that do not exist here (user registration, /oauth2, /index.html).
        // Only the bearer tokens of the service accounts are accepted, plus the webhooks and return links.
        http
                .authorizeHttpRequests()
                .requestMatchers(
                        "/api/webhooks/**", // public: webhooks and return links of the gateways
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui/index.html"
                ).permitAll()
                // Audit P2: the payment routes, for the allowed service accounts only (when the list is set).
                .requestMatchers("/api/payments/**").access((authentication, context) ->
                        new org.springframework.security.authorization.AuthorizationDecision(allowedClients.allows(authentication.get())))
                .anyRequest().authenticated();

        return http.build();
    }
}
