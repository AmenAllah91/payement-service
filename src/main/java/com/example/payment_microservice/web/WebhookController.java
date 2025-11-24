package com.example.payment_microservice.web;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;
import com.example.payment_microservice.service.gateways.PayPalHandler;
import com.example.payment_microservice.service.gateways.StripeHandler;
import com.paypal.orders.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final YosalesFeign yosalesFeign;
    private final PayPalHandler payPalHandler;
    private final PaymentGatewayFactory gatewayFactory;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TokenService tokenService;

    /**
     * Generic webhook handler for POST-based webhooks (PayPal, Stripe)
     */
    @PostMapping("/{productId}/{gatewayType}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable Long productId,
            @PathVariable String gatewayType,
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestHeader Map<String, String> headers) {

        long startTime = System.currentTimeMillis();

        try {
            log.info("Webhook received: productId={}, gateway={}", productId, gatewayType);
            CompletableFuture.runAsync(() -> {
                try {
                    PaymentGatewayHandler handler = gatewayFactory.getHandler(gatewayType.toUpperCase());
                    if ("STRIPE".equalsIgnoreCase(gatewayType) && handler instanceof StripeHandler) {
                        ((StripeHandler) handler).handleWebhook(payload, stripeSignature,productId);
                    } else {
                        handler.handleWebhook(payload);
                    }
                    log.info("Webhook processed for gateway: {}", gatewayType);
                } catch (Exception e) {
                    log.error("Webhook processing error for gateway: {}", gatewayType, e);
                }
            });

            long duration = System.currentTimeMillis() - startTime;
            log.info("Webhook response sent in {}ms", duration);

            return ResponseEntity
                    .status(HttpStatus.OK)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("OK");

        } catch (Exception e) {
            log.error("Error receiving webhook", e);
            // Still return 200 to prevent retries
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("OK");
        }
    }

    /**
     * Konnect webhook handler (GET-based with payment_ref)
     */
    @GetMapping("/konnect/{productId}")
    public ResponseEntity<String> handleKonnectWebhook(
            @PathVariable Long productId, // Changed from String to Long
            @RequestParam("payment_ref") String paymentRef) {

        try {
            log.info("Konnect webhook: productId={}, paymentRef={}", productId, paymentRef);
            CompletableFuture.runAsync(() -> {
                try {
                    PaymentGatewayHandler handler = gatewayFactory.getHandler("KONNECT");
                    handler.handleWebhook(paymentRef);
                } catch (Exception e) {
                    log.error("Konnect webhook processing error", e);
                }
            });

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Konnect webhook receive error", e);
            return ResponseEntity.ok("OK");
        }
    }
    /**
     * PayPal return URL handler - captures order after user approval (v2)
     */
    @GetMapping("/payment/paypal/return")
    public ResponseEntity<?> handlePayPalReturn(
            @RequestParam("token") String orderId) {

        try {
            log.info("PayPal return: orderId={}", orderId);
            Optional<PaymentTransaction> txOpt =
                    paymentTransactionRepository.findByTransactionId(orderId);

            if (txOpt.isEmpty()) {
                log.error("Transaction not found for orderId: {}", orderId);
                return redirectToFrontend("/payment/failed?error=transaction_not_found");
            }

            PaymentTransaction record = txOpt.get();
            if ("COMPLETED".equals(record.getStatus())) {
                log.info("Order already captured: {}", orderId);
                return redirectToFrontend("/payment/success?invoiceId=" + record.getInvoiceId());
            }

            // 3. Capture the order (replaces v1 execute)
            Order capturedOrder = payPalHandler.captureOrder(orderId, record.getInvoiceId());

            // 4. Update transaction
            if ("COMPLETED".equals(capturedOrder.status())) {
                record.setStatus("PROCESSING");
                record.setUpdatedAt(LocalDateTime.now());
                paymentTransactionRepository.save(record);

                log.info("Order captured successfully: {}", orderId);
                return redirectToFrontend("/payment/success?invoiceId=" + record.getInvoiceId());
            } else {
                log.warn("Order not completed: status={}", capturedOrder.status());
                return redirectToFrontend("/payment/failed?error=not_completed");
            }

        } catch (Exception e) {
            log.error("Error capturing order: {}", orderId, e);
            return redirectToFrontend("/payment/failed?error=server_error");
        }
    }

    private ResponseEntity<?> redirectToFrontend(String path) {
        String frontendUrl = "http://localhost:4200" + path;
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(frontendUrl))
                .build();
    }
}
