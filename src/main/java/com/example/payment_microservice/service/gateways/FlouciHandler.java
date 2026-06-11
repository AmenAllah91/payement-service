package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.*;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.example.payment_microservice.config.PaymentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class FlouciHandler implements PaymentGatewayHandler {

    private final PaymentProperties paymentProperties;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final YosalesFeign yosalesFeign;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception {
        try {
            validateRequest(request);

            String appPublic = request.getClientId() != null ? request.getClientId() : request.getApiKey();
            String appSecret = request.getClientSecret() != null ? request.getClientSecret() : request.getApiKey();

            if (appPublic == null || appSecret == null) {
                throw new IllegalArgumentException("Flouci public token (clientId) and private token (clientSecret) are required");
            }

            FlouciPaymentRequest body = new FlouciPaymentRequest();
            body.setAmount(request.getAmount() * 1000); // Convert DT to millimes
            body.setSuccessLink(request.getSuccesUrl());
            body.setFailLink(request.getFailUrl());
            body.setAcceptCard(true);
            body.setWebhook(request.getWebhookUrl());
            body.setDeveloperTrackingId(request.getPaymentId().toString());
            body.setImageUrl(request.getBrandLogo());
            body.setClientId(request.getUserId());

            WebClient webClient = webClientBuilder
                    .baseUrl(paymentProperties.getFlouci().getBaseUrl())
                    .build();

            FlouciResponse res = webClient.post()
                    .uri("/generate_payment")
                    .header("Authorization", "Bearer " + appPublic + ":" + appSecret)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Flouci API error: " + err))))
                    .bodyToMono(FlouciResponse.class)
                    .block();

            if (res == null || res.getResult() == null || !res.getResult().isSuccess()) {
                throw new RuntimeException("Flouci API returned an unsuccessful status");
            }

            log.info("Flouci payment initiated: payment_id={}", res.getResult().getPaymentId());

            PaymentTransaction tx = PaymentTransaction.builder()
                    .paymentId(request.getPaymentId()) // YoSales payment ID
                    .transactionId(res.getResult().getPaymentId()) // Flouci payment_id
                    .gatewayType("FLOUCI")
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(request.getAmount()))
                    .currency(request.getCurrency() != null ? request.getCurrency() : "TND")
                    .description(request.getDescription())
                    .invoiceId(request.getInvoiceId())
                    .customerId(request.getUserId())
                    .build();

            paymentTransactionRepository.save(tx);

            return new PaymentResponseDto(
                    res.getResult().getPaymentId(),
                    res.getResult().getLink(),
                    "PENDING",
                    "Redirect to Flouci Checkout"
            );

        } catch (Exception e) {
            log.error("Error initiating Flouci payment", e);
            throw new Exception("Error initiating Flouci payment: " + e.getMessage(), e);
        }
    }

    private void validateRequest(PaymentRequestDto request) {
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
        try {
            log.info("Flouci webhook received: payload={}", payload);
            String flouciPaymentId = null;

            if (payload != null && payload.trim().startsWith("{")) {
                JsonNode rootNode = objectMapper.readTree(payload);
                if (rootNode.has("details") && rootNode.get("details").has("order_number")) {
                    flouciPaymentId = rootNode.get("details").get("order_number").asText();
                }
            } else if (payload != null) {
                flouciPaymentId = payload.trim();
            }

            if (flouciPaymentId == null || flouciPaymentId.isEmpty()) {
                log.error("Flouci webhook payload missing details.order_number / payment ID");
                return;
            }

            Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByTransactionId(flouciPaymentId);
            if (txOpt.isEmpty()) {
                log.error("Transaction not found for Flouci payment_id: {}", flouciPaymentId);
                return;
            }

            PaymentTransaction tx = txOpt.get();
            verifyAndCompletePayment(tx);

        } catch (Exception e) {
            log.error("Error processing Flouci webhook", e);
        }
    }

    public void handleWebhookSync(String paymentId) {
        try {
            log.info("Flouci synchronous webhook verification for paymentId={}", paymentId);
            Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByTransactionId(paymentId);
            if (txOpt.isPresent()) {
                verifyAndCompletePayment(txOpt.get());
            } else {
                log.error("Transaction not found for Flouci payment_id: {}", paymentId);
            }
        } catch (Exception e) {
            log.error("Error in Flouci synchronous webhook verification", e);
        }
    }

    private boolean verifyAndCompletePayment(PaymentTransaction tx) throws Exception {
        Map<String, Object> credentials = getBillingCredentials(tx.getInvoiceId());

        String appPublic = (String) credentials.get("appToken");
        if (appPublic == null) appPublic = (String) credentials.get("clientId");
        if (appPublic == null) appPublic = (String) credentials.get("publicKey");

        String appSecret = (String) credentials.get("appSecret");
        if (appSecret == null) appSecret = (String) credentials.get("clientSecret");
        if (appSecret == null) appSecret = (String) credentials.get("secretKey");
        if (appSecret == null) appSecret = (String) credentials.get("apiKey");

        if (appPublic == null || appSecret == null) {
            log.error("Flouci credentials missing for invoiceId: {}", tx.getInvoiceId());
            throw new IllegalStateException("Flouci credentials missing");
        }

        WebClient webClient = webClientBuilder
                .baseUrl(paymentProperties.getFlouci().getBaseUrl())
                .build();

        FlouciVerificationResponse res = webClient.get()
                .uri("/verify_payment/" + tx.getTransactionId())
                .header("Authorization", "Bearer " + appPublic + ":" + appSecret)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                        .flatMap(err -> Mono.error(new IllegalStateException("Flouci API error: " + err))))
                .bodyToMono(FlouciVerificationResponse.class)
                .block();

        if (res == null || !res.isSuccess() || res.getResult() == null) {
            log.error("Failed to verify payment with Flouci for transaction: {}", tx.getTransactionId());
            throw new IllegalStateException("Invalid response from Flouci");
        }

        String status = res.getResult().getStatus();
        log.info("Flouci verification status for payment_id {}: {}", tx.getTransactionId(), status);

        if ("SUCCESS".equalsIgnoreCase(status)) {
            tx.setStatus("COMPLETED");
            tx.setGatewayResponse(objectMapper.writeValueAsString(res));
            paymentTransactionRepository.save(tx);

            try {
                yosalesFeign.markInvoicePaid(
                        "Bearer " + tokenService.getServiceAccountToken(),
                        tx.getInvoiceId()
                );
                log.info("Invoice {} marked as paid for Flouci payment: {}", tx.getInvoiceId(), tx.getTransactionId());
                return true;
            } catch (Exception e) {
                log.error("Failed to mark invoice as paid", e);
                throw new IllegalStateException("Payment verified but invoice update failed", e);
            }
        } else if ("FAILURE".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)) {
            tx.setStatus("FAILED");
            tx.setFailureReason("Flouci transaction status: " + status);
            tx.setGatewayResponse(objectMapper.writeValueAsString(res));
            paymentTransactionRepository.save(tx);
            log.info("Flouci payment failed: {} status={}", tx.getTransactionId(), status);
            return false;
        } else {
            log.info("Flouci payment is still pending: {}", tx.getTransactionId());
            return false;
        }
    }

    @Override
    public Boolean verifyCredentials(Map<String, Object> credentials) {
        String appPublic = (String) credentials.get("appPublic")   ;
        if (appPublic == null) appPublic = (String) credentials.get("appToken");
        if (appPublic == null) appPublic = (String) credentials.get("clientId");

        String appSecret = (String) credentials.get("appSecret");
        if (appSecret == null) appSecret = (String) credentials.get("clientSecret");
        if (appSecret == null) appSecret = (String) credentials.get("secretKey");
        if (appSecret == null) appSecret = (String) credentials.get("appSecret");

        if (appPublic == null || appSecret == null) {
            log.warn("Flouci credentials missing appPublic or appSecret");
            return false;
        }

        try {
            WebClient webClient = webClientBuilder
                    .baseUrl(paymentProperties.getFlouci().getBaseUrl())
                    .build();

            FlouciPaymentRequest body = new FlouciPaymentRequest();
            body.setAmount(100L); // 100 millimes (0.1 TND)
            body.setSuccessLink("https://example.com/success");
            body.setFailLink("https://example.com/fail");
            body.setWebhook("https://example.com/webhook");
            body.setDeveloperTrackingId("verify_credentials_test");

            FlouciResponse res = webClient.post()
                    .uri("/generate_payment")
                    .header("Authorization", "Bearer " + appPublic + ":" + appSecret)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Flouci API error: " + err))))
                    .bodyToMono(FlouciResponse.class)
                    .block();

            log.info("Flouci credentials verified successfully: {}", res);
            return res != null && res.getResult() != null && res.getResult().isSuccess();

        } catch (WebClientResponseException.Unauthorized e) {
            log.error("Flouci credentials invalid - unauthorized", e);
            return false;
        } catch (Exception e) {
            log.error("Flouci credential verification failed", e);
            return false;
        }
    }

    @Override
    public Boolean getPaymentStatusByInvoiceId(String transactionId) throws Exception {
        PaymentTransaction paymentTransaction = paymentTransactionRepository
                .findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Payment transaction not found for invoiceId: " + transactionId));

        if ("COMPLETED".equals(paymentTransaction.getStatus())) {
            return true;
        }

        return verifyAndCompletePayment(paymentTransaction);
    }


    private Map<String, Object> getBillingCredentials(Long invoiceId) {
        return yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                "Bearer " + tokenService.getServiceAccountToken(),
                invoiceId,
                "FLOUCI"
        );
    }
}
