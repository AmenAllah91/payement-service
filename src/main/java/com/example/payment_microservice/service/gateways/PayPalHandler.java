package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypal.core.PayPalEnvironment;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PayPalHandler implements PaymentGatewayHandler {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final YosalesFeign yosalesFeign;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    @Value("${payment.webhook-url::https://postpuberty-trigonometric-bev.ngrok-free.dev}")
    private String paymentBaseUrl;

    @Value("${payment.paypal.mode}")
    private String mode;

    @Override
    @Transactional
    public PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception {
        try {
            PayPalHttpClient client = createPayPalClient(
                    request.getClientId(),
                    request.getClientSecret(),
                    mode

            );
            OrderRequest orderRequest = buildOrderRequest(request);
            OrdersCreateRequest createRequest = new OrdersCreateRequest();
            createRequest.prefer("return=representation");
            createRequest.requestBody(orderRequest);
            HttpResponse<Order> response = client.execute(createRequest);
            Order order = response.result();
            String approvalUrl = order.links().stream()
                    .filter(link -> "approve".equals(link.rel()))
                    .findFirst()
                    .map(LinkDescription::href)
                    .orElseThrow(() -> new Exception("Approval URL not found"));
            log.info("PayPal order created: {}", order.id());
            PaymentTransaction tx = PaymentTransaction.builder()
                    .paymentId(request.getPaymentId()) // YoSales payment ID
                    .transactionId(order.id())
                    .gatewayType("PAYPAL")
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(request.getAmount()))
                    .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                    .description(request.getDescription())
                    .invoiceId(request.getInvoiceId())
                    .customerId(request.getUserId())
                    .build();
            paymentTransactionRepository.save(tx);
            return new PaymentResponseDto(
                    order.id(),
                    approvalUrl,
                    "PENDING",
                    "Redirect to PayPal Checkout"
            );

        } catch (Exception e) {
            log.error("Error creating PayPal order", e);
            throw new Exception("Error creating PayPal order: " + e.getMessage(), e);
        }
    }

    public PayPalHttpClient createPayPalClient(String clientId, String clientSecret, String mode) {
        PayPalEnvironment environment;

        if ("live".equalsIgnoreCase(mode)) {
            environment = new PayPalEnvironment.Live(clientId, clientSecret);
        } else {
            environment = new PayPalEnvironment.Sandbox(clientId, clientSecret);
        }

        return new PayPalHttpClient(environment);
    }

    private OrderRequest buildOrderRequest(PaymentRequestDto request) {
        OrderRequest orderRequest = new OrderRequest();
        orderRequest.checkoutPaymentIntent("CAPTURE");

        ApplicationContext applicationContext = new ApplicationContext()
                .returnUrl(paymentBaseUrl + "/api/webhooks/payment/paypal/return")
                .cancelUrl(paymentBaseUrl + "/api/webhooks/payment/paypal/cancel")
                .brandName(request.getBrandName())
                .landingPage("BILLING")
                .shippingPreference("NO_SHIPPING")
                .userAction("PAY_NOW");

        orderRequest.applicationContext(applicationContext);
        List<PurchaseUnitRequest> purchaseUnits = new ArrayList<>();

        BigDecimal amountValue = new BigDecimal(request.getAmount().toString())
                .setScale(2, RoundingMode.HALF_UP);

        String currency = request.getCurrency() != null ? request.getCurrency() : "USD";

        AmountWithBreakdown amountBreakdown = new AmountWithBreakdown()
                .currencyCode(currency)
                .value(amountValue.toString());

        PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest()
                .description(request.getDescription())
                .customId(String.valueOf(request.getInvoiceId()))
                .invoiceId(request.getInvoiceId().toString())
                .amountWithBreakdown(amountBreakdown);

        purchaseUnits.add(purchaseUnit);
        orderRequest.purchaseUnits(purchaseUnits);

        return orderRequest;
    }

    /**
     * Capture order after user approval
     */
    public Order captureOrder(String orderId, Long invoiceId) throws Exception {
        try {
            Map<String, Object> credentials = getBillingCredentials(invoiceId);
            String clientId = (String) credentials.get("clientId");
            String clientSecret = (String) credentials.get("clientSecret");
            PayPalHttpClient client = createPayPalClient(clientId, clientSecret, this.mode);
            OrdersCaptureRequest captureRequest = new OrdersCaptureRequest(orderId);
            captureRequest.prefer("return=representation");
            HttpResponse<Order> response = client.execute(captureRequest);
            Order capturedOrder = response.result();
            log.info("PayPal order captured: {}, status: {}",
                    capturedOrder.id(), capturedOrder.status());
            return capturedOrder;
        } catch (Exception e) {
            log.error("Error capturing PayPal order: {}", orderId, e);
            throw new Exception("Failed to capture order: " + e.getMessage(), e);
        }
    }

    @Override
    @Async
    public void handleWebhook(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.get("event_type").asText();
            log.info("PayPal v2 webhook received: {}", eventType);

            switch (eventType) {
                case "CHECKOUT.ORDER.APPROVED":
                    handleOrderApproved(event);
                    break;

                case "PAYMENT.CAPTURE.COMPLETED":
                    handleCaptureCompleted(event);
                    break;

                case "PAYMENT.CAPTURE.PENDING":
                    handleCapturePending(event);
                    break;

                case "PAYMENT.CAPTURE.DENIED":
                    handleCaptureDenied(event);
                    break;

                case "PAYMENT.CAPTURE.REFUNDED":
                    handleCaptureRefunded(event);
                    break;

                default:
                    log.info("Unhandled v2 event: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing v2 webhook", e);
        }
    }

    private void handleOrderApproved(JsonNode event) {
        try {
            JsonNode resource = event.get("resource");
            String orderId = resource.get("id").asText();

            log.info("Order approved: {}", orderId);
            Optional<PaymentTransaction> txOpt =
                    paymentTransactionRepository.findByTransactionId(orderId);
            txOpt.ifPresent(tx -> {
                tx.setStatus("APPROVED");
                paymentTransactionRepository.save(tx);
                log.info("Transaction updated to APPROVED for order: {}", orderId);
            });

        } catch (Exception e) {
            log.error("Error handling order approved event", e);
        }
    }

    /**
     * Handle capture completed event - THIS IS THE MAIN SUCCESS EVENT
     */
    private void handleCaptureCompleted(JsonNode event) {
        try {
            JsonNode resource = event.get("resource");
            String captureId = resource.get("id").asText();
            String status = resource.get("status").asText();
            JsonNode supplementaryData = resource.get("supplementary_data");
            String orderId = null;

            if (supplementaryData != null && supplementaryData.has("related_ids")) {
                JsonNode relatedIds = supplementaryData.get("related_ids");
                if (relatedIds.has("order_id")) {
                    orderId = relatedIds.get("order_id").asText();
                }
            }

            log.info("Capture completed: captureId={}, orderId={}, status={}",
                    captureId, orderId, status);

            if (orderId != null && "COMPLETED".equals(status)) {
                String finalOrderId = orderId;
                Optional<PaymentTransaction> txOpt =
                        paymentTransactionRepository.findByTransactionId(finalOrderId);

                txOpt.ifPresentOrElse(
                        tx -> {
                            tx.setStatus("COMPLETED");
                            tx.setTransactionId(captureId);
                            paymentTransactionRepository.save(tx);

                            try {
                                yosalesFeign.markInvoicePaid(
                                        "Bearer " + tokenService.getServiceAccountToken(),
                                        tx.getInvoiceId()
                                );
                                log.info("Invoice {} marked as paid", tx.getInvoiceId());
                            } catch (Exception e) {
                                log.error("Failed to mark invoice as paid", e);
                            }
                        },
                        () -> log.error("Transaction not found for orderId: {}", finalOrderId)
                );
            }

        } catch (Exception e) {
            log.error("Error handling capture completed event", e);
        }
    }

    /**
     * Handle capture pending event
     */
    private void handleCapturePending(JsonNode event) {
        try {
            JsonNode resource = event.get("resource");
            String captureId = resource.get("id").asText();
            JsonNode supplementaryData = resource.get("supplementary_data");
            String orderId = null;

            if (supplementaryData != null && supplementaryData.has("related_ids")) {
                JsonNode relatedIds = supplementaryData.get("related_ids");
                if (relatedIds.has("order_id")) {
                    orderId = relatedIds.get("order_id").asText();
                }
            }

            log.warn("Capture pending: captureId={}, orderId={}", captureId, orderId);

            if (orderId != null) {
                String finalOrderId = orderId;
                Optional<PaymentTransaction> txOpt =
                        paymentTransactionRepository.findByTransactionId(finalOrderId);

                txOpt.ifPresent(tx -> {
                    tx.setStatus("PENDING_REVIEW");
                    tx.setFailureReason("Payment under review by PayPal");
                    paymentTransactionRepository.save(tx);
                    log.info("Transaction updated to PENDING_REVIEW for order: {}", finalOrderId);
                });
            }

        } catch (Exception e) {
            log.error("Error handling capture pending event", e);
        }
    }

    private void handleCaptureDenied(JsonNode event) {
        try {
            JsonNode resource = event.get("resource");
            String captureId = resource.get("id").asText();
            JsonNode supplementaryData = resource.get("supplementary_data");
            String orderId = null;

            if (supplementaryData != null && supplementaryData.has("related_ids")) {
                JsonNode relatedIds = supplementaryData.get("related_ids");
                if (relatedIds.has("order_id")) {
                    orderId = relatedIds.get("order_id").asText();
                }
            }

            log.error("Capture denied: captureId={}, orderId={}", captureId, orderId);

            if (orderId != null) {
                String finalOrderId = orderId;
                Optional<PaymentTransaction> txOpt =
                        paymentTransactionRepository.findByTransactionId(finalOrderId);

                txOpt.ifPresent(tx -> {
                    tx.setStatus("FAILED");
                    tx.setFailureReason("Payment denied by PayPal");
                    paymentTransactionRepository.save(tx);
                    log.info("Transaction updated to FAILED for order: {}", finalOrderId);
                });
            }

        } catch (Exception e) {
            log.error("Error handling capture denied event", e);
        }
    }
    private void handleCaptureRefunded(JsonNode event) {
        try {
            JsonNode resource = event.get("resource");
            String refundId = resource.get("id").asText();
            JsonNode supplementaryData = resource.get("supplementary_data");
            String orderId = null;

            if (supplementaryData != null && supplementaryData.has("related_ids")) {
                JsonNode relatedIds = supplementaryData.get("related_ids");
                if (relatedIds.has("order_id")) {
                    orderId = relatedIds.get("order_id").asText();
                }
            }

            log.info("Refund processed: refundId={}, orderId={}", refundId, orderId);

            if (orderId != null) {
                Optional<PaymentTransaction> txOpt =
                        paymentTransactionRepository.findByTransactionId(orderId);

                txOpt.ifPresent(tx -> {
                    tx.setStatus("REFUNDED");
                    tx.setFailureReason("Payment refunded");
                    paymentTransactionRepository.save(tx);
                    try {
                        yosalesFeign.markInvoiceRefunded(
                                "Bearer " + tokenService.getServiceAccountToken(),
                                tx.getInvoiceId()
                        );
                        log.info("Invoice {} marked as refunded", tx.getInvoiceId());
                    } catch (Exception e) {
                        log.error("Failed to mark invoice as refunded", e);
                    }
                });
            }

        } catch (Exception e) {
            log.error("Error handling refund event", e);
        }
    }

    @Override
    public Boolean verifyCredentials(Map<String, Object> credentials) {
        try {
            String clientId = (String) credentials.get("clientId");
            String clientSecret = (String) credentials.get("clientSecret");
            String mode = this.mode;

            if (clientId == null || clientSecret == null) {
                log.warn("PayPal credentials missing clientId or clientSecret");
                return false;
            }

            PayPalHttpClient client = createPayPalClient(clientId, clientSecret, mode);
            OrderRequest testOrder = new OrderRequest();
            testOrder.checkoutPaymentIntent("CAPTURE");

            List<PurchaseUnitRequest> units = new ArrayList<>();
            PurchaseUnitRequest unit = new PurchaseUnitRequest()
                    .amountWithBreakdown(new AmountWithBreakdown()
                            .currencyCode("USD")
                            .value("1.00"));
            units.add(unit);
            testOrder.purchaseUnits(units);

            OrdersCreateRequest request = new OrdersCreateRequest();
            request.requestBody(testOrder);
            client.execute(request);

            log.info("PayPal credentials verified successfully");
            return true;

        } catch (Exception e) {
            log.error("PayPal credential verification failed", e);
            return false;
        }
    }

    @Override
    public Boolean getPaymentStatusByInvoiceId(String invoiceId) throws Exception {
        return null;
    }

    private Map<String, Object> getBillingCredentials(Long invoiceId) {
        return yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                "Bearer " + tokenService.getServiceAccountToken(),
                invoiceId,
                "PAYPAL"
        );
    }
}
