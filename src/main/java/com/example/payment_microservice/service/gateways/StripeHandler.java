package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.BillingConfigurationDto;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeHandler implements PaymentGatewayHandler {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final YosalesFeign yosalesFeign;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    @Value("${payment.stripe.mode}")
    private String mode;
    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception {
        try {
            String successUrl = (request.getSuccesUrl() != null && !request.getSuccesUrl().isEmpty())
                    ? request.getSuccesUrl()
                    : "http://localhost:4200?session_id={CHECKOUT_SESSION_ID}";

            String cancelUrl = (request.getFailUrl() != null && !request.getFailUrl().isEmpty())
                    ? request.getFailUrl()
                    : "http://localhost:4200";
            validateRequest(request);
            Stripe.apiKey = request.getApiKey();
            String currency = request.getCurrency() != null ? request.getCurrency().toLowerCase() : "usd";
            long amountInCents =(request.getAmount() * 100);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("paymentId", String.valueOf(request.getPaymentId()));
            metadata.put("invoiceId", String.valueOf(request.getInvoiceId()));
            metadata.put("userId", request.getUserId());
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .putAllMetadata(metadata)
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency(currency)
                                                    .setUnitAmount(amountInCents)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName(request.getDescription() != null ?
                                                                            request.getDescription() : "Invoice Payment")
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            Session session = Session.create(params);

            log.info("Stripe session created: sessionId={}, url={}", session.getId(), session.getUrl());
            PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                    .paymentId(request.getPaymentId())
                    .transactionId(session.getId())
                    .gatewayType("STRIPE")
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(request.getAmount()))
                    .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                    .description(request.getDescription())
                    .invoiceId(request.getInvoiceId())
                    .customerId(request.getUserId())
                    .build();

           PaymentTransaction paymentTransaction1= paymentTransactionRepository.save(paymentTransaction);
            log.info("Payment transaction saved: paymentId={}, transactionId={}, invoiceId={}",
                    request.getPaymentId(), session.getId(), request.getInvoiceId());

            return new PaymentResponseDto(
                    paymentTransaction1.getTransactionId(),
                    session.getUrl(),
                    "PENDING",
                    "Redirect to Stripe Checkout"
            );

        } catch (StripeException e) {
            log.error("Error creating Stripe Checkout session", e);
            throw new Exception("Error creating Stripe Checkout session: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            throw new Exception("Validation error: " + e.getMessage(), e);
        }
    }

    /**
     * Validate payment request
     */
    private void validateRequest(PaymentRequestDto request) {
        if (request.getApiKey() == null || request.getApiKey().isEmpty()) {
            throw new IllegalArgumentException("Stripe secret key is required");
        }
        boolean isTestKey = request.getApiKey().startsWith("sk_test_");
        boolean isLiveKey = request.getApiKey().startsWith("sk_live_");

        if ("live".equals(mode) && !isLiveKey) {
            throw new IllegalArgumentException("Live mode requires live API key");
        }
        if ("sandbox".equals(mode) && !isTestKey) {
            throw new IllegalArgumentException("Test mode requires Test API keys");

        }
        if (request.getPaymentId() == null) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("invoiceId is required");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Valid amount is required");
        }
        if (request.getUserId() == null || request.getUserId().isEmpty()) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    @Override
    @Async
    public void handleWebhook(String payload) {
        handleWebhook(payload, null,null);
    }

    /**
     * Handle Stripe webhook with signature verification
     */
    /**
     * Handle Stripe webhook with signature verification
     */
    public void handleWebhook(String payload, String stripeSignature,Long productId) {
        try {
            BillingConfigurationDto config = this.yosalesFeign.getBillingInfoByproductIdAndGateway("Bearer "+tokenService.getServiceAccountToken(), String.valueOf(productId),"STRIPE");
            log.info("Stripe webhook received");
            String webhokSecret =  (String) config.getConfigParams().get("webhookSecret");
            if (stripeSignature != null && webhokSecret != null) {
                boolean isValid = verifyWebhookSignature(payload, stripeSignature,webhokSecret);
                if (!isValid) {
                    log.error("Invalid Stripe webhook signature - rejecting event");
                    return;
                }
                log.info("Stripe webhook signature verified successfully");
            } else {
                log.warn("Stripe webhook received without signature verification ");
            }
            JsonNode eventJson = objectMapper.readTree(payload);
            String eventType = eventJson.get("type").asText();

            log.info("Processing Stripe event: {}", eventType);

            switch (eventType) {
                case "checkout.session.completed" -> handleCheckoutSessionCompleted(eventJson);
                case "checkout.session.async_payment_succeeded" -> handleAsyncPaymentSucceeded(eventJson);
                case "checkout.session.async_payment_failed" -> handleAsyncPaymentFailed(eventJson);
                case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(eventJson);
                case "payment_intent.payment_failed" -> handlePaymentIntentFailed(eventJson);
                case "charge.refunded" -> handleChargeRefunded(eventJson);
                default -> log.info("Unhandled Stripe event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing Stripe webhook", e);
        }
    }

    /**
     * Handle checkout session completed
     */
    private void handleCheckoutSessionCompleted(JsonNode eventJson) {
        try {
            JsonNode sessionData = eventJson.get("data").get("object");

            String sessionId = sessionData.get("id").asText();
            String paymentStatus = sessionData.get("payment_status").asText();

            // Get metadata
            JsonNode metadata = sessionData.get("metadata");
            Long invoiceId = metadata.has("invoiceId") ?
                    Long.parseLong(metadata.get("invoiceId").asText()) : null;
            Long paymentId = metadata.has("paymentId") ?
                    Long.parseLong(metadata.get("paymentId").asText()) : null;

            log.info("Checkout session completed: sessionId={}, status={}, paymentId={}, invoiceId={}",
                    sessionId, paymentStatus, paymentId, invoiceId);

            if ("paid".equals(paymentStatus)) {
                Optional<PaymentTransaction> txOpt =
                        paymentTransactionRepository.findByTransactionId(sessionId);

                txOpt.ifPresentOrElse(
                        tx -> {
                            tx.setStatus("COMPLETED");
                            tx.setGatewayResponse(eventJson.toString());
                            paymentTransactionRepository.save(tx);

                            // Mark invoice as paid
                            if (tx.getInvoiceId() != null) {
                                try {
                                    yosalesFeign.markInvoicePaid(
                                            "Bearer " + tokenService.getServiceAccountToken(),
                                            tx.getInvoiceId()
                                    );
                                    log.info("Invoice {} marked as paid for Stripe session: {}",
                                            tx.getInvoiceId(), sessionId);
                                } catch (Exception e) {
                                    log.error("Failed to mark invoice as paid: {}", tx.getInvoiceId(), e);
                                }
                            }
                        },
                        () -> log.warn("Transaction not found for Stripe session: {}", sessionId)
                );
            } else {
                log.warn("Payment status is not 'paid': sessionId={}, status={}", sessionId, paymentStatus);
            }

        } catch (Exception e) {
            log.error("Error handling checkout session completed", e);
        }
    }

    /**
     * Handle async payment succeeded (for delayed payment methods like bank transfers)
     */
    private void handleAsyncPaymentSucceeded(JsonNode eventJson) {
        try {
            JsonNode sessionData = eventJson.get("data").get("object");
            String sessionId = sessionData.get("id").asText();

            log.info("Async payment succeeded: {}", sessionId);

            paymentTransactionRepository.findByTransactionId(sessionId)
                    .ifPresent(tx -> {
                        tx.setStatus("COMPLETED");
                        tx.setGatewayResponse(eventJson.toString());
                        paymentTransactionRepository.save(tx);
                        try {
                            yosalesFeign.markInvoicePaid(
                                    "Bearer " + tokenService.getServiceAccountToken(),
                                    tx.getInvoiceId()
                            );
                            log.info("Invoice {} marked as paid (async)", tx.getInvoiceId());
                        } catch (Exception e) {
                            log.error("Failed to mark invoice as paid", e);
                        }
                    });

        } catch (Exception e) {
            log.error("Error handling async payment succeeded", e);
        }
    }

    /**
     * Handle async payment failed
     */
    private void handleAsyncPaymentFailed(JsonNode eventJson) {
        try {
            JsonNode sessionData = eventJson.get("data").get("object");
            String sessionId = sessionData.get("id").asText();

            log.error("Async payment failed: {}", sessionId);

            paymentTransactionRepository.findByTransactionId(sessionId)
                    .ifPresent(tx -> {
                        tx.setStatus("FAILED");
                        tx.setFailureReason("Async payment failed");
                        tx.setGatewayResponse(eventJson.toString());
                        paymentTransactionRepository.save(tx);
                    });

        } catch (Exception e) {
            log.error("Error handling async payment failed", e);
        }
    }

    private void handlePaymentIntentSucceeded(JsonNode eventJson) {
        try {
            JsonNode piData = eventJson.get("data").get("object");
            String paymentIntentId = piData.get("id").asText();

            log.info("Payment intent succeeded: {}", paymentIntentId);
        } catch (Exception e) {
            log.error("Error handling payment intent succeeded", e);
        }
    }

    private void handlePaymentIntentFailed(JsonNode eventJson) {
        try {
            JsonNode piData = eventJson.get("data").get("object");
            String paymentIntentId = piData.get("id").asText();

            log.error("Payment intent failed: {}", paymentIntentId);
        } catch (Exception e) {
            log.error("Error handling payment intent failed", e);
        }
    }

    private void handleChargeRefunded(JsonNode eventJson) {
        try {
            JsonNode chargeData = eventJson.get("data").get("object");
            String chargeId = chargeData.get("id").asText();

            log.info("Charge refunded: {}", chargeId);
            // gere le refund d'un invoice
        } catch (Exception e) {
            log.error("Error handling charge refunded", e);
        }
    }

    @Override
    public Boolean verifyCredentials(Map<String, Object> credentials) {
        String secretKey = (String) credentials.get("apiKey");

        if (secretKey == null || secretKey.isEmpty()) {
            log.warn("Stripe secret key is missing");
            return false;
        }

        try {
            Stripe.apiKey = secretKey;
            Account account = Account.retrieve();

            log.info("Stripe credentials verified successfully for account: {}", account.getId());
            return account.getId() != null;

        } catch (StripeException e) {
            log.error("Stripe credential verification failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean verifyWebhookSignature(String payload, String signature, String webhookSecret) {
        try {
            Webhook.constructEvent(payload, signature, webhookSecret);
            return true;
        } catch (SignatureVerificationException e) {
            log.error("Invalid Stripe webhook signature", e);
            return false;
        }
    }
}
