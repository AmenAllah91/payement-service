package com.example.payment_microservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Audit P2: who may call {@code /api/payments/**}. With {@code app.security.allowed-clients} set (the Keycloak client
 * of YoSales, e.g. "yo-sales-backend"), only a token issued to one of those clients ({@code azp}) is accepted: a coach
 * or any account of the realm can no longer create transactions, reconcile or test keys. Empty (the default) = any
 * signed-in caller, as before, until every caller goes through YoSales.
 */
@Component
public class AllowedClients {

    private final Set<String> clients;

    public AllowedClients(@Value("${app.security.allowed-clients:}") String clients) {
        this.clients = clients == null ? Set.of() : Arrays.stream(clients.split(","))
                .map(String::trim).filter(c -> !c.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean enabled() {
        return !clients.isEmpty();
    }

    public boolean allows(Authentication authentication) {
        // A request without token reaches here as an AnonymousAuthenticationToken, which says isAuthenticated() = true.
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return false;
        }
        if (!enabled()) {
            return true;
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String azp = jwt.getToken().getClaimAsString("azp");
            return azp != null && clients.contains(azp);
        }
        return false;
    }
}
