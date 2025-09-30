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
    private  JwtAuthConverter jwtAuthConverter;

    @Value("${cors.allowedMethods}")
    private String allowedMethods;

    @Value("${cors.allowedHeaders}")
    private String allowedHeaders;

    @Value("${cors.corsConfiguration}")
    private String corsConfiguration;

    @Bean
    public WebMvcConfigurer corsConfigurer(){
        return new WebMvcConfigurer(){
            public void addCorsMappings(final CorsRegistry registry){
                registry.addMapping(corsConfiguration).allowedHeaders(allowedHeaders).allowedMethods(allowedMethods);
            }
        };
    }
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .sessionManagement()
                .sessionCreationPolicy(STATELESS)
                .and()
                .oauth2ResourceServer()
                .jwt()
                .jwtAuthenticationConverter(jwtAuthConverter);
        http
                .oauth2Client()
                .and()
                .authorizeHttpRequests()
                .requestMatchers("/unauthenticated", "/oauth2/**", "/index.html", "/v3/api-docs/**" ,"/swagger-ui/**" , "/swagger-ui/index.html" ,"/public/register" )
                .permitAll()
                .anyRequest()
                .authenticated()
                .and()
                .oauth2Login(withDefaults())
                .logout()
                .logoutSuccessUrl("http://localhost:9081/realms/coach-app/protocol/openid-connect/logout?redirect_uri=http://localhost:8080/");
        return http.build();
    }
}


