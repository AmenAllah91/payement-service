package com.example.payment_microservice.web;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;
import com.example.payment_microservice.service.gateways.FlouciHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final YosalesFeign yosalesFeign;
    private final PaymentGatewayFactory gatewayFactory;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TokenService tokenService;

    /** Where the coach lands after paying (was hard-coded to localhost:4200). */
    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:4200}")
    private String frontendBaseUrl;

    /**
     * Generic webhook handler for POST-based webhooks (Flouci, Stripe). Only the payment methods the service supports
     * are accepted: any other one (PayPal and Konnect were removed) is refused at once, nothing is processed.
     * Audit P5 (2026-10-26): handled before answering (it used to be answered 200 at once and handled in the
     * background, so a failure was lost): 200 once handled, 400 for a refused signature, 500 when it must be sent again.
     */
    @PostMapping("/{productId}/{gatewayType}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable Long productId,
            @PathVariable String gatewayType,
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
            @RequestHeader Map<String, String> headers) {

        long startTime = System.currentTimeMillis();

        Optional<PaymentGatewayHandler> supported = supportedHandler(gatewayType);
        if (supported.isEmpty()) {
            log.warn("Webhook refused: unsupported gateway {} (productId={})", gatewayType, productId);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Unsupported payment gateway");
        }
        PaymentGatewayHandler handler = supported.get();

        log.info("Webhook received: productId={}, gateway={}", productId, gatewayType);
        try {
            PaymentGatewayHandler.WebhookOutcome outcome = handler.handleWebhook(payload, stripeSignature, productId);
            log.info("Webhook {} for gateway {} in {}ms", outcome, gatewayType, System.currentTimeMillis() - startTime);
            if (outcome == PaymentGatewayHandler.WebhookOutcome.REJECTED) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("Rejected");
            }
            return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.TEXT_PLAIN).body("OK");
        } catch (Exception e) {
            // The gateway sends the event again; handling it twice is harmless (state read again, YoSales idempotent).
            log.error("Webhook processing error for gateway {}: it will be sent again", gatewayType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("Retry");
        }
    }

    @GetMapping("/{productId}/flouci")
    public ResponseEntity<?> handleFlouciRedirect(
            @PathVariable Long productId,
            @RequestParam(value = "payment_id", required = false) String paymentIdParam,
            @RequestParam(value = "invoiceId", required = false) String invoiceIdParam) {

        String paymentId = flouciPaymentId(paymentIdParam, invoiceIdParam);
        if (paymentId == null) {
            log.error("Flouci redirect without payment_id: productId={}, invoiceId={}", productId, invoiceIdParam);
            return redirectToFrontend("/payment/failed?error=transaction_not_found");
        }
        try {
            log.info("Flouci redirect webhook: productId={}, paymentId={}", productId, paymentId);
            PaymentGatewayHandler handler = gatewayFactory.getHandler("FLOUCI");

            boolean checked = true;
            if (handler instanceof FlouciHandler) {
                checked = ((FlouciHandler) handler).handleWebhookSync(paymentId);
            } else {
                handler.handleWebhook(paymentId);
            }

            // Only a Flouci transaction answers a Flouci return link.
            Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByTransactionId(paymentId)
                    .filter(tx -> FlouciHandler.GATEWAY.equals(tx.getGatewayType()));
            if (txOpt.isPresent()) {
                PaymentTransaction tx = txOpt.get();
                // Audit MP5: back to the front of the product of this payment, not always to the YoCoach one.
                String front = frontOf(tx);
                // VERIFIED = paid at Flouci, YoSales confirmation pending (it is retried): the coach did pay.
                if ("COMPLETED".equals(tx.getStatus()) || "VERIFIED".equals(tx.getStatus())) {
                    return redirectTo(front, "/payment/success?invoiceId=" + tx.getInvoiceId());
                } else if (!checked && "PENDING".equals(tx.getStatus())) {
                    // Audit 2026-10-26: not read (Flouci or YoSales down): the return page asks the status until it is
                    // known, instead of "failed" for a coach who may have paid (and would pay again).
                    return redirectTo(front, "/payment/success?invoiceId=" + tx.getInvoiceId());
                } else {
                    // SUB-23: the invoice lets the return page offer "try again" for the right invoice.
                    return redirectTo(front, "/payment/failed?error=not_completed&invoiceId=" + tx.getInvoiceId());
                }
            }
            return redirectToFrontend("/payment/failed?error=transaction_not_found");

        } catch (Exception e) {
            log.error("Flouci redirect handler error", e);
            return redirectToFrontend("/payment/failed?error=server_error");
        }
    }

    /**
     * The Flouci payment id of a return link. YoSales already puts {@code ?invoiceId=X} on the link, so Flouci may add
     * its id as {@code &payment_id=} or glue it as {@code ?invoiceId=X?payment_id=}: both are read.
     */
    static String flouciPaymentId(String paymentId, String invoiceId) {
        if (paymentId != null && !paymentId.isBlank()) {
            return paymentId.trim();
        }
        if (invoiceId != null && invoiceId.contains("payment_id=")) {
            String glued = invoiceId.substring(invoiceId.indexOf("payment_id=") + "payment_id=".length());
            int end = glued.indexOf('&');
            glued = end >= 0 ? glued.substring(0, end) : glued;
            return glued.isBlank() ? null : glued.trim();
        }
        return null;
    }

    private Optional<PaymentGatewayHandler> supportedHandler(String gatewayType) {
        try {
            return Optional.of(gatewayFactory.getHandler(gatewayType.toUpperCase()));
        } catch (IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }

    /** The front given by YoSales for the product of the transaction, else the default address of the service. */
    private String frontOf(PaymentTransaction tx) {
        String returnUrl = tx.getReturnUrl();
        return returnUrl == null || returnUrl.isBlank() ? frontendBaseUrl : returnUrl;
    }

    private ResponseEntity<?> redirectToFrontend(String path) {
        return redirectTo(frontendBaseUrl, path);
    }

    private ResponseEntity<?> redirectTo(String front, String path) {
        String frontendUrl = front + path;
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(frontendUrl))
                .build();
    }
}
