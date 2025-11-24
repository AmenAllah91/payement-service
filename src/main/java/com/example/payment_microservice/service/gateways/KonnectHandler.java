package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.KonnectPaymentRequest;
import com.example.payment_microservice.dto.KonnectResponse;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.example.payment_microservice.config.PaymentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class KonnectHandler implements PaymentGatewayHandler {

    private final PaymentProperties paymentProperties;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final YosalesFeign yosalesFeign;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Value("${payment.webhook-url}")
    private String webhookUrl;

    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto req) throws Exception {
        try {
            KonnectPaymentRequest body = new KonnectPaymentRequest();
            body.setReceiverWalletId(req.getWalletId());
            body.setToken(req.getCurrency() != null ? req.getCurrency() : "TND");
            body.setAmount(req.getAmount() * 1000);
            body.setType("immediate");
            body.setDescription(req.getDescription());
            body.setAcceptedPaymentMethods(Arrays.asList("wallet", "bank_card", "e-DINAR"));
            body.setLifespan(10);
            body.setCheckoutForm(true);
            body.setAddPaymentFeesToAmount(true);
            body.setFirstName(req.getFirstName());
            body.setLastName(req.getLastName());
            body.setPhoneNumber(req.getPhoneNumber());
            body.setEmail(req.getEmail());
            body.setOrderId(req.getPaymentId().toString());
            body.setWebhook(req.getWebhookUrl());
            body.setSilentWebhook(true);
            body.setSuccessUrl(req.getSuccesUrl());
            body.setFailUrl(req.getFailUrl());
            body.setTheme("light");
            WebClient webClient = webClientBuilder
                    .baseUrl(paymentProperties.getKonnect().getBaseUrl())
                    .build();
            KonnectResponse res = webClient.post()
                    .uri("/payments/init-payment")
                    .header("x-api-key", req.getApiKey())
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Konnect API error: " + err))))
                    .bodyToMono(KonnectResponse.class)
                    .block();

            assert res != null;
            log.info("Konnect payment initiated: paymentRef={}", res.getPaymentRef());
            PaymentTransaction tx = PaymentTransaction.builder()
                    .paymentId(req.getPaymentId()) // YoSales payment ID
                    .transactionId(res.getPaymentRef()) // Konnect paymentRef
                    .gatewayType("KONNECT")
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(req.getAmount()))
                    .currency(req.getCurrency() != null ? req.getCurrency() : "TND")
                    .description(req.getDescription())
                    .invoiceId(req.getInvoiceId())
                    .customerId(req.getUserId())
                    .build();
            paymentTransactionRepository.save(tx);
            return new PaymentResponseDto(
                    res.getPaymentRef(),
                    res.getPayUrl(),
                    "PENDING",
                    "Redirect to Konnect Checkout"
            );

        } catch (Exception e) {
            log.error("Error initiating Konnect payment", e);
            throw new Exception("Error initiating Konnect payment: " + e.getMessage(), e);
        }
    }

    @Override
    @Async
    public void handleWebhook(String payload) {
        try {
            log.info("Konnect webhook received: paymentRef={}", payload);
            getKonnectPaymentDetails(payload);
        } catch (Exception e) {
            log.error("Error processing Konnect webhook", e);
        }
    }

    /**
     * Fetch payment details from Konnect API
     */
    private void getKonnectPaymentDetails(String paymentRef) {
        try {
            Optional<PaymentTransaction> txOpt = paymentTransactionRepository
                    .findByTransactionId(paymentRef);

            if (txOpt.isEmpty()) {
                log.error("Transaction not found for paymentRef: {}", paymentRef);
                return;
            }

            PaymentTransaction tx = txOpt.get();
            Map<String, Object> credentials = getBillingCredentials(tx.getInvoiceId());
            String apiKey = (String) credentials.get("apiKey");

            WebClient webClient = webClientBuilder
                    .baseUrl(paymentProperties.getKonnect().getBaseUrl())
                    .build();
            String url = "/payments/" + paymentRef;

            webClient.get()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Konnect API error: " + err))))
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> handlePaymentDetails(response, tx),
                            error -> log.error("Error fetching payment details from Konnect: {}", paymentRef, error)
                    );

        } catch (Exception e) {
            log.error("Error getting Konnect payment details for paymentRef: {}", paymentRef, e);
        }
    }

    /**
     * Process payment details response from Konnect
     */
    private void handlePaymentDetails(String responseJson, PaymentTransaction tx) {
        try {
            JsonNode response = objectMapper.readTree(responseJson);
            String status = response.get("payment").get("status").asText();

            log.info("Konnect payment status: paymentRef={}, status={}", tx.getTransactionId(), status);

            switch (status) {
                case "completed":
                    handlePaymentCompleted(tx, response);
                    break;

                case "pending":
                    handlePaymentPending(tx);
                    break;

                case "failed":
                    handlePaymentFailed(tx, response);
                    break;

                default:
                    log.info("Unhandled Konnect payment status: {}", status);
            }

        } catch (Exception e) {
            log.error("Error handling Konnect payment details", e);
        }
    }

    /**
     * Handle completed payment
     */
    private void handlePaymentCompleted(PaymentTransaction tx, JsonNode response) {
        try {
            tx.setStatus("COMPLETED");
            tx.setGatewayResponse(response.toString());
            paymentTransactionRepository.save(tx);
            try {
                yosalesFeign.markInvoicePaid(
                        "Bearer " + tokenService.getServiceAccountToken(),
                        tx.getInvoiceId()
                );
                log.info("Invoice {} marked as paid for Konnect payment: {}",
                        tx.getInvoiceId(), tx.getTransactionId());
            } catch (Exception e) {
                log.error("Failed to mark invoice as paid", e);
            }

        } catch (Exception e) {
            log.error("Error handling completed Konnect payment", e);
        }
    }

    /**
     * Handle pending payment
     */
    private void handlePaymentPending(PaymentTransaction tx) {
        try {
            tx.setStatus("PENDING_REVIEW");
            tx.setFailureReason("Payment pending");
            paymentTransactionRepository.save(tx);

            log.info("Konnect payment pending: {}", tx.getTransactionId());

        } catch (Exception e) {
            log.error("Error handling pending Konnect payment", e);
        }
    }

    /**
     * Handle failed payment
     */
    private void handlePaymentFailed(PaymentTransaction tx, JsonNode response) {
        try {
            String failureReason = response.has("payment") && response.get("payment").has("result")
                    ? response.get("payment").get("result").asText()
                    : "Payment failed";

            tx.setStatus("FAILED");
            tx.setFailureReason(failureReason);
            tx.setGatewayResponse(response.toString());
            paymentTransactionRepository.save(tx);

            log.error("Konnect payment failed: {}, reason: {}", tx.getTransactionId(), failureReason);

        } catch (Exception e) {
            log.error("Error handling failed Konnect payment", e);
        }
    }

    @Override
    public Boolean verifyCredentials(Map<String, Object> credentials) {
        String apiKey = (String) credentials.get("accessToken");
        String walletId = (String) credentials.get("merchantId");

        if (apiKey == null || walletId == null) {
            log.warn("Konnect credentials missing apiKey or walletId");
            return false;
        }

        try {
            WebClient webClient = webClientBuilder
                    .baseUrl(paymentProperties.getKonnect().getBaseUrl())
                    .build();
             KonnectPaymentRequest body = new KonnectPaymentRequest();
                body.setReceiverWalletId(walletId);
                body.setToken( "TND");
                body.setAmount(100);
                body.setType("immediate");
                body.setDescription("for verify credentials");
                body.setAcceptedPaymentMethods(Arrays.asList("wallet", "bank_card", "e-DINAR"));
                body.setLifespan(10);
                body.setCheckoutForm(true);
                body.setAddPaymentFeesToAmount(true);
                body.setFirstName("test");
                body.setLastName("test");
                body.setPhoneNumber("test");
                body.setEmail("test");
                body.setOrderId("test");
                body.setWebhook(this.webhookUrl);
                body.setSilentWebhook(true);
                body.setSuccessUrl(paymentProperties.getKonnect().getSuccessUrl());
                body.setFailUrl(paymentProperties.getKonnect().getFailUrl());
                body.setTheme("light");

                KonnectResponse res = webClient.post()
                    .uri("/payments/init-payment")
                    .header("x-api-key", apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .flatMap(err -> Mono.error(new RuntimeException("Konnect API error: " + err))))
                    .bodyToMono(KonnectResponse.class)
                    .block();

            log.info("Konnect credentials verified successfully: {}", res);
            return true;

        } catch (WebClientResponseException.Unauthorized e) {
            log.error("Konnect credentials invalid - unauthorized", e);
            return false;
        } catch (WebClientResponseException.UnprocessableEntity e) {
            log.error("Konnect request validation failed: {}", e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("Konnect credential verification failed", e);
            return false;
        }
    }


    /**
     * Get billing credentials from YoSales
     */
    private Map<String, Object> getBillingCredentials(Long invoiceId) {
        return yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                "Bearer " + tokenService.getServiceAccountToken(),
                invoiceId,
                "KONNECT"
        );
    }
}
