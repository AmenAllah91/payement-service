package com.example.payment_microservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit P2: /api/payments/** for the service account of YoSales only, once the list is set. */
class AllowedClientsTest {

    private static JwtAuthenticationToken tokenOf(String azp) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("someone")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            jwt.claim("azp", azp);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    @Test
    void withTheListSetOnlyTheListedClientsAreAccepted() {
        AllowedClients allowed = new AllowedClients(" yo-sales-backend , other-service ");

        assertThat(allowed.enabled()).isTrue();
        assertThat(allowed.allows(tokenOf("yo-sales-backend"))).isTrue();
        assertThat(allowed.allows(tokenOf("other-service"))).isTrue();
        assertThat(allowed.allows(tokenOf("yo-coach-front"))).isFalse(); // a coach signed in on the front
        assertThat(allowed.allows(tokenOf(null))).isFalse();
        assertThat(allowed.allows(new UsernamePasswordAuthenticationToken("u", "p", List.of()))).isFalse();
    }

    @Test
    void withoutListAnySignedInCallerIsAcceptedAsBefore() {
        AllowedClients allowed = new AllowedClients("");

        assertThat(allowed.enabled()).isFalse();
        assertThat(allowed.allows(tokenOf("yo-coach-front"))).isTrue();
        assertThat(allowed.allows(null)).isFalse();
    }

    /** A request without token is anonymous (and Spring says "authenticated" for it): always refused. */
    @Test
    void aCallerWithoutTokenIsNeverAccepted() {
        var anonymous = new org.springframework.security.authentication.AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(anonymous.isAuthenticated()).isTrue();

        assertThat(new AllowedClients("").allows(anonymous)).isFalse();
        assertThat(new AllowedClients("yo-sales-backend").allows(anonymous)).isFalse();
    }
}
