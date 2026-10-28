package com.example.payment_microservice.web;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.dto.VerificationRequest;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentGatewayFactory gatewayFactory;
    private final com.example.payment_microservice.repositories.PaymentTransactionRepository transactions;
    private final com.example.payment_microservice.security.AllowedClients allowedClients;


    @PostMapping("/initiate")
    public ResponseEntity<PaymentResponseDto> initiatePayment(@RequestBody PaymentRequestDto request) {
        try {
            // Review 2026-10-26: the page the customer comes back to is trusted only when the callers are restricted
            // to YoSales (allowed clients); otherwise the front of this service, as before: never an open redirect.
            request.setReturnUrl(allowedClients.enabled() ? validReturnUrl(request.getReturnUrl()) : null);
            PaymentGatewayHandler handler = gatewayFactory.getHandler(request.getGatewayType());
            PaymentResponseDto response = handler.initiatePayment(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new PaymentResponseDto(null, null, "FAILED", e.getMessage()));
        }
    }
    /** An absolute http(s) address with a host, without user info, query or fragment; null otherwise. */
    static String validReturnUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            return web && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    ? url.trim() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** SUB-62: charges the saved card of the subscription (no page, the coach is not there). */
    @PostMapping("/charge-saved")
    public ResponseEntity<PaymentResponseDto> chargeSavedCard(@RequestBody PaymentRequestDto request) {
        try {
            return ResponseEntity.ok(gatewayFactory.getHandler(request.getGatewayType()).chargeSavedCard(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new PaymentResponseDto(null, null, "FAILED", e.getMessage()));
        }
    }

    /** SUB-62: link to the Stripe page where the coach saves a new card. */
    @PostMapping("/setup-card")
    public ResponseEntity<PaymentResponseDto> setupCard(@RequestBody PaymentRequestDto request) {
        try {
            return ResponseEntity.ok(gatewayFactory.getHandler(request.getGatewayType()).setupCard(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new PaymentResponseDto(null, null, "FAILED", e.getMessage()));
        }
    }

    /**
     * Paid or not, for a transaction (YoSales sends the transaction id of the latest payment of the invoice). Audit P14
     * (2026-10-26): asked to the gateway that created it; it was always Flouci, so a Stripe payment was never paid here.
     */
    @GetMapping("/{transactionId}/status-payment")
    public ResponseEntity<Boolean> getStatusPayment(@PathVariable String transactionId) {
        try {
            String status = gatewayFactory.getHandler(gatewayOf(transactionId)).reconcile(transactionId);
            return ResponseEntity.ok("COMPLETED".equals(status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    /** SUB-52: called by YoSales for a payment pending for a while; answers the state of the transaction. */
    @GetMapping("/{transactionId}/reconcile")
    public ResponseEntity<Map<String, String>> reconcile(@PathVariable String transactionId) {
        try {
            String status = gatewayFactory.getHandler(gatewayOf(transactionId)).reconcile(transactionId);
            return ResponseEntity.ok(Map.of("status", status == null ? "PENDING" : status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** SUB-61: the gateway that created the transaction (Flouci or Stripe), whose handler answers for it. */
    private String gatewayOf(String transactionId) {
        return transactions.findByTransactionId(transactionId)
                .map(PaymentTransaction::getGatewayType)
                .orElseThrow(() -> new IllegalArgumentException("Payment transaction not found: " + transactionId));
    }

    @PostMapping("/verify-credentials")
    public ResponseEntity<Boolean> verifyCredentials(
            @RequestBody    VerificationRequest verificationRequest) {
        try {
            String gatewayType = verificationRequest.getGatewayType();
            Map<String, Object> credentials = verificationRequest.getConfigParms();
            PaymentGatewayHandler handler = gatewayFactory.getHandler(gatewayType);
            Boolean valid = handler.verifyCredentials(credentials);
            return ResponseEntity.ok(valid);
        } catch (Exception e) {
            return ResponseEntity.ok(false);
        }
    }


}
