package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.BillingConfigurationDto;
import com.example.payment_microservice.dto.PaymentConfirmationRequest;
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
            // SUB-61: YoSales sends the exact amount in minor units of the invoice currency (cents for USD).
            long amountInCents = amountInMinorUnits(request);
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
                    .amount(BigDecimal.valueOf(amountInCents, 2))
                    .amountMillimes(amountInCents)
                    .currency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD")
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
        boolean hasMinorUnits = request.getAmountMillimes() != null && request.getAmountMillimes() > 0;
        if (!hasMinorUnits && (request.getAmount() == null || request.getAmount() <= 0)) {
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
            // SUB-61: never trust an unsigned webhook (anyone could post a fake "paid" event).
            if (stripeSignature == null || webhokSecret == null || webhokSecret.isBlank()) {
                log.error("Stripe webhook rejected: missing signature or webhook secret");
                return;
            }
            if (!verifyWebhookSignature(payload, stripeSignature, webhokSecret)) {
                log.error("Invalid Stripe webhook signature - rejecting event");
                return;
            }
            JsonNode eventJson = objectMapper.readTree(payload);
            String eventType = eventJson.get("type").asText();

            log.info("Processing Stripe event: {}", eventType);

            switch (eventType) {
                // SUB-61: whatever the event says, the session is read again at Stripe and the result is confirmed to
                // YoSales by the single confirmation path (amount and currency checked there).
                case "checkout.session.completed", "checkout.session.async_payment_succeeded",
                     "checkout.session.async_payment_failed", "checkout.session.expired" -> sessionEvent(eventJson);
                case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(eventJson);
                case "payment_intent.payment_failed" -> handlePaymentIntentFailed(eventJson);
                case "charge.refunded" -> handleChargeRefunded(eventJson);
                default -> log.info("Unhandled Stripe event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing Stripe webhook", e);
        }
    }

    private void sessionEvent(JsonNode eventJson) {
        try {
            String sessionId = eventJson.get("data").get("object").get("id").asText();
            paymentTransactionRepository.findByTransactionId(sessionId).ifPresentOrElse(
                    this::verifyAndConfirm,
                    () -> log.warn("Transaction not found for Stripe session: {}", sessionId));
        } catch (Exception e) {
            log.error("Error handling Stripe session event", e);
        }
    }

    /** What YoSales needs from a Checkout Session, read at Stripe (never from the webhook payload). */
    public record SessionState(String status, String paymentStatus, Long amountTotal, String currency) {
    }

    /** Reads the session at Stripe with the secret key of the product (overridden in tests). */
    protected SessionState retrieveSession(String apiKey, String sessionId) throws StripeException {
        Session session = Session.retrieve(sessionId, com.stripe.net.RequestOptions.builder().setApiKey(apiKey).build());
        return new SessionState(session.getStatus(), session.getPaymentStatus(), session.getAmountTotal(), session.getCurrency());
    }

    /**
     * SUB-61: checks the session at Stripe and sends the result to YoSales: paid -> SUCCESS with the amount Stripe
     * collected; expired or failed -> FAILED; still open -> nothing. COMPLETED and REJECTED are final. Safe to repeat.
     */
    String verifyAndConfirm(PaymentTransaction tx) {
        if ("COMPLETED".equals(tx.getStatus()) || "REJECTED".equals(tx.getStatus())) {
            return tx.getStatus();
        }
        try {
            Map<String, Object> credentials = yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                    "Bearer " + tokenService.getServiceAccountToken(), tx.getInvoiceId(), "STRIPE");
            Object apiKey = credentials == null ? null : credentials.get("apiKey");
            if (apiKey == null) {
                throw new IllegalStateException("Stripe credentials missing for invoice " + tx.getInvoiceId());
            }
            SessionState session = retrieveSession(apiKey.toString(), tx.getTransactionId());
            if ("paid".equals(session.paymentStatus()) || "no_payment_required".equals(session.paymentStatus())) {
                if (!"COMPLETED".equals(tx.getStatus())) {
                    tx.setStatus("VERIFIED");
                    paymentTransactionRepository.save(tx);
                }
                confirmToYoSales(tx, "SUCCESS", session.amountTotal(), session.currency());
            } else if ("expired".equals(session.status())) {
                tx.setStatus("FAILED");
                tx.setFailureReason("Stripe session expired");
                paymentTransactionRepository.save(tx);
                confirmToYoSales(tx, "FAILED", session.amountTotal(), session.currency());
            } else {
                log.info("Stripe session {} still {} / {}", tx.getTransactionId(), session.status(), session.paymentStatus());
            }
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe API error: " + e.getMessage(), e);
        }
        return tx.getStatus();
    }

    /** Sends the verified result to YoSales (SUB-10); YoSales checks amount and currency and ignores a repeat. */
    void confirmToYoSales(PaymentTransaction tx, String status, Long amountMinorUnits, String currency) {
        PaymentConfirmationRequest body = new PaymentConfirmationRequest(tx.getInvoiceId(), tx.getPaymentId(),
                tx.getTransactionId(), status, amountMinorUnits, currency == null ? tx.getCurrency() : currency.toUpperCase());
        try {
            Map<String, String> answer = yosalesFeign.confirmPayment("Bearer " + tokenService.getServiceAccountToken(), body);
            log.info("YoSales answer for Stripe session {} ({}): {}", tx.getTransactionId(), status, answer);
            if ("SUCCESS".equals(status)) {
                tx.setStatus("COMPLETED");
                tx.setCompletedAt(java.time.LocalDateTime.now());
                paymentTransactionRepository.save(tx);
            }
        } catch (feign.FeignException.Conflict refused) {
            log.error("YoSales refused the confirmation of Stripe session {}: {}", tx.getTransactionId(), refused.contentUTF8());
            if ("SUCCESS".equals(status)) {
                tx.setStatus("REJECTED");
                tx.setFailureReason("YoSales refused the confirmation: " + refused.contentUTF8());
                paymentTransactionRepository.save(tx);
            }
        } catch (Exception e) {
            log.error("Could not send the confirmation of Stripe session {} to YoSales, it will be sent again",
                    tx.getTransactionId(), e);
        }
    }

    /** SUB-52: reconciliation of a pending payment asked by YoSales. */
    @Override
    public String reconcile(String transactionId) {
        PaymentTransaction tx = paymentTransactionRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Payment transaction not found: " + transactionId));
        return verifyAndConfirm(tx);
    }

    /** Exact amount in minor units: the value sent by YoSales, or the legacy whole amount x 100. */
    static long amountInMinorUnits(PaymentRequestDto request) {
        if (request.getAmountMillimes() != null && request.getAmountMillimes() > 0) {
            return request.getAmountMillimes();
        }
        return request.getAmount() * 100;
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

    @Override
    public Boolean getPaymentStatusByInvoiceId(String invoiceId) throws Exception {
        return null;
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
