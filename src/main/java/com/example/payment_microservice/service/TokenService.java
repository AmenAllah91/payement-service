package com.example.payment_microservice.service;

import com.example.payment_microservice.config.Env;
import com.example.payment_microservice.feign.KeycloakPublicClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final Env env;

    private final KeycloakPublicClient keycloakPublicClient;
    public String getServiceAccountToken() {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("grant_type", "client_credentials");
        map.add("client_id", env.getCLIENT_ID());
        map.add("client_secret", env.getCLIENT_SECRET());

        Map<String, Object> tokenMap = keycloakPublicClient.getServiceAccountToken(map, env.getREALM());

        if (tokenMap == null || !tokenMap.containsKey("access_token")) {
            throw new RuntimeException("Access token retrieval failed");
        }

        return (String) tokenMap.get("access_token");
    }
}
