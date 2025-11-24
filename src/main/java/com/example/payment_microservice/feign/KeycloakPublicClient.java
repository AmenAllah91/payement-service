package com.example.payment_microservice.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "keycloak-public", url = "${app.kc-host}"+"/realms/"+"${app.realm}")
public interface KeycloakPublicClient {

    @PostMapping(value = "/protocol/openid-connect/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Map<String, Object> getServiceAccountToken(@RequestBody Map<String, ?> formParams, @RequestParam("realm") String realm);
    @GetMapping("/realms/{realm}/protocol/openid-connect/userinfo")
    Map<String, Object> getUserInfo(@RequestHeader("Authorization") String bearerToken, @PathVariable("realm") String realm);
}