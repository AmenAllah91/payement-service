package com.example.payment_microservice.web;

import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentGatewayFactory gatewayFactory;

    @PostMapping("/{gatewayType}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable String gatewayType, @RequestBody String payload) {
        try {
            PaymentGatewayHandler handler = gatewayFactory.getHandler(gatewayType);
            handler.handleWebhook(payload);
            return ResponseEntity.ok("Webhook processed successfully");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed to process webhook");
        }
    }
}
