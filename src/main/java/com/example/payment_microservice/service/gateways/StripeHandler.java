package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.CardSetup;
import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.SavedCardDto;
import com.example.payment_microservice.dto.PaymentConfirmationRequest;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.CardException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeHandler implements PaymentGatewayHandler {

    /** Gateway type of the transactions this handler created; it never touches another one. */
    public static final String GATEWAY = "STRIPE";

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final YosalesFeign yosalesFeign;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final com.example.payment_microservice.repositories.CardSetupRepository cardSetups;

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
            String currency = request.getCurrency() != null ? request.getCurrency().toLowerCase() : "usd";
            // SUB-61: YoSales sends the exact amount in minor units of the invoice currency (cents for USD).
            long amountInCents = amountInMinorUnits(request);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("paymentId", String.valueOf(request.getPaymentId()));
            metadata.put("invoiceId", String.valueOf(request.getInvoiceId()));
            metadata.put("userId", request.getUserId());
            SessionCreateParams.Builder sessionBuilder = SessionCreateParams.builder()
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
                    );
            saveCardFor(sessionBuilder, request);

            // Audit P6: the key of this product is passed with the call, never set for the whole service.
            StartedSession session = startCheckoutSession(request.getApiKey(), sessionBuilder.build());

            log.info("Stripe session created: sessionId={}, url={}", session.id(), session.url());
            PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                    .paymentId(request.getPaymentId())
                    .transactionId(session.id())
                    .gatewayType(GATEWAY)
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(amountInCents, 2))
                    .amountMillimes(amountInCents)
                    .currency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD")
                    .description(request.getDescription())
                    .invoiceId(request.getInvoiceId())
                    .returnUrl(request.getReturnUrl())
                    .customerId(request.getUserId())
                    .build();

           PaymentTransaction paymentTransaction1= paymentTransactionRepository.save(paymentTransaction);
            log.info("Payment transaction saved: paymentId={}, transactionId={}, invoiceId={}",
                    request.getPaymentId(), session.id(), request.getInvoiceId());

            return new PaymentResponseDto(
                    paymentTransaction1.getTransactionId(),
                    session.url(),
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
    /**
     * Audit P7: "sandbox" or "test" (any case) = test keys only; anything else, typos included, = live keys only. A
     * mode that is not recognised never turns the check off.
     */
    boolean sandboxMode() {
        String value = mode == null ? "" : mode.trim();
        return "sandbox".equalsIgnoreCase(value) || "test".equalsIgnoreCase(value);
    }

    void requireKeyOfMode(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Stripe secret key is required");
        }
        if (sandboxMode() && !apiKey.startsWith("sk_test_")) {
            throw new IllegalArgumentException("Test mode requires Test API keys");
        }
        if (!sandboxMode() && !apiKey.startsWith("sk_live_")) {
            throw new IllegalArgumentException("Live mode requires live API key");
        }
    }

    private void validateRequest(PaymentRequestDto request) {
        if (request.getApiKey() == null || request.getApiKey().isEmpty()) {
            throw new IllegalArgumentException("Stripe secret key is required");
        }
        requireKeyOfMode(request.getApiKey());
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
    public void handleWebhook(String payload) {
        handleWebhook(payload, null, null);
    }

    /**
     * A Stripe webhook, checked with the signing secret of the product. Audit P5 (2026-10-26): handled while Stripe
     * waits for the answer, and a failure is thrown (answered 500) so that Stripe sends the event again; a repeat is
     * harmless (the session is read again, YoSales ignores a confirmation it already applied).
     */
    @Override
    public WebhookOutcome handleWebhook(String payload, String stripeSignature, Long productId) {
        if (stripeSignature == null || stripeSignature.isBlank()) {
            // Checked first: an unsigned post costs nothing (no token asked, no call to YoSales).
            log.error("Stripe webhook rejected: missing signature");
            return WebhookOutcome.REJECTED;
        }
        // Audit P3: the route reserved to this service account (the admin one answered 403 and masks the keys).
        Map<String, Object> credentials = this.yosalesFeign.getCredentialsByProductAndGateway(
                "Bearer " + tokenService.getServiceAccountToken(), productId, GATEWAY);
        log.info("Stripe webhook received");
        String webhokSecret = credentials == null ? null : (String) credentials.get("webhookSecret");
        // SUB-61: never trust an unsigned webhook (anyone could post a fake "paid" event).
        if (webhokSecret == null || webhokSecret.isBlank()) {
            log.error("Stripe webhook rejected: no webhook secret for product {}", productId);
            return WebhookOutcome.REJECTED;
        }
        if (!verifyWebhookSignature(payload, stripeSignature, webhokSecret)) {
            log.error("Invalid Stripe webhook signature - rejecting event");
            return WebhookOutcome.REJECTED;
        }
        JsonNode eventJson;
        try {
            eventJson = objectMapper.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            log.error("Stripe webhook rejected: unreadable payload");
            return WebhookOutcome.REJECTED;
        }
        String eventType = eventJson.get("type").asText();

        log.info("Processing Stripe event: {}", eventType);

        switch (eventType) {
            // SUB-61: whatever the event says, the session is read again at Stripe and the result is confirmed to
            // YoSales by the single confirmation path (amount and currency checked there).
            case "checkout.session.completed", "checkout.session.async_payment_succeeded",
                 "checkout.session.async_payment_failed", "checkout.session.expired" -> sessionEvent(eventJson);
            // SUB-62: a saved card charged without the coach (the PaymentIntent is the transaction).
            case "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.canceled" -> paymentIntentEvent(eventJson);
            case "charge.refunded" -> handleChargeRefunded(eventJson);
            default -> log.info("Unhandled Stripe event type: {}", eventType);
        }
        return WebhookOutcome.HANDLED;
    }

    private void sessionEvent(JsonNode eventJson) {
        String sessionId = eventJson.get("data").get("object").get("id").asText();
        Optional<PaymentTransaction> payment = paymentTransactionRepository.findByTransactionId(sessionId);
        if (payment.isPresent()) {
            requireDelivered(verifyAndConfirm(payment.get()), sessionId);
            return;
        }
        // SUB-62: a session where the coach saved a new card.
        cardSetups.findBySessionId(sessionId).ifPresentOrElse(
                this::verifyCardSetup,
                () -> log.warn("Transaction not found for Stripe session: {}", sessionId));
    }

    /**
     * Audit P5: paid at Stripe but YoSales did not get the confirmation (VERIFIED): the webhook fails so that Stripe
     * sends it again, on top of the reconciliation of YoSales.
     */
    private static void requireDelivered(String status, String transactionId) {
        if ("VERIFIED".equals(status)) {
            throw new IllegalStateException("Stripe payment " + transactionId + " is paid but not confirmed to YoSales yet");
        }
    }

    /** SUB-62: the card used by a payment, read at Stripe (references to charge it again, and what the coach sees). */
    public record CardState(String customerRef, String paymentMethodRef, String brand, String last4, Long expMonth, Long expYear) {
        SavedCardDto toDto() {
            return new SavedCardDto(customerRef, paymentMethodRef, brand, last4, expMonth, expYear);
        }
    }

    /**
     * What YoSales needs from a Checkout Session or from the PaymentIntent of a saved card, read at Stripe (never from
     * the webhook payload). {@code failureCode}: why Stripe refused (card_declined, expired_card...).
     */
    public record SessionState(String status, String paymentStatus, Long amountTotal, String currency, CardState card,
                               String failureCode) {
        public SessionState(String status, String paymentStatus, Long amountTotal, String currency) {
            this(status, paymentStatus, amountTotal, currency, null, null);
        }

        boolean paid() {
            return "paid".equals(paymentStatus) || "no_payment_required".equals(paymentStatus);
        }
    }

    /**
     * Reads a Checkout Session (cs_...) or the PaymentIntent of a saved card (pi_...) at Stripe with the secret key of
     * the product (overridden in tests).
     */
    protected SessionState retrieveSession(String apiKey, String id) throws StripeException {
        RequestOptions options = RequestOptions.builder().setApiKey(apiKey).build();
        if (id.startsWith("pi_")) {
            Map<String, Object> expand = new HashMap<>();
            expand.put("expand", List.of("payment_method"));
            return stateOf(PaymentIntent.retrieve(id, expand, options));
        }
        Session session = Session.retrieve(id, SessionRetrieveParams.builder()
                .addExpand("payment_intent.payment_method")
                .addExpand("setup_intent.payment_method")
                .build(), options);
        PaymentMethod method = null;
        if (session.getPaymentIntentObject() != null) {
            method = session.getPaymentIntentObject().getPaymentMethodObject();
        } else if (session.getSetupIntentObject() != null) {
            method = session.getSetupIntentObject().getPaymentMethodObject();
        }
        return new SessionState(session.getStatus(), session.getPaymentStatus(), session.getAmountTotal(),
                session.getCurrency(), cardOf(session.getCustomer(), method), null);
    }

    /**
     * SUB-62: state of the PaymentIntent of a saved card. Without the coach, a card that asks for an authentication
     * (3-D Secure) is a failure: the invoice stays payable by link.
     */
    static SessionState stateOf(PaymentIntent pi) {
        String status = pi.getStatus();
        CardState card = cardOf(pi.getCustomer(), pi.getPaymentMethodObject());
        if ("succeeded".equals(status)) {
            return new SessionState("complete", "paid", pi.getAmountReceived(), pi.getCurrency(), card, null);
        }
        if ("canceled".equals(status) || "requires_payment_method".equals(status) || "requires_action".equals(status)) {
            String code = null;
            if (pi.getLastPaymentError() != null) {
                code = pi.getLastPaymentError().getDeclineCode() != null ? pi.getLastPaymentError().getDeclineCode()
                        : pi.getLastPaymentError().getCode();
            }
            if (code == null && "requires_action".equals(status)) {
                code = "authentication_required";
            }
            return new SessionState("expired", "unpaid", pi.getAmount(), pi.getCurrency(), card, code);
        }
        return new SessionState("open", "unpaid", pi.getAmount(), pi.getCurrency(), card, null); // processing
    }

    static CardState cardOf(String customer, PaymentMethod method) {
        if (customer == null || method == null) {
            return null;
        }
        PaymentMethod.Card card = method.getCard();
        return new CardState(customer, method.getId(), card == null ? null : card.getBrand(), card == null ? null : card.getLast4(),
                card == null ? null : card.getExpMonth(), card == null ? null : card.getExpYear());
    }

    /**
     * SUB-61: checks the payment at Stripe and sends the result to YoSales: paid -> SUCCESS with the amount Stripe
     * collected (and the saved card, SUB-62); expired or refused -> FAILED; still open -> nothing. COMPLETED and
     * REJECTED are final. Safe to repeat.
     */
    String verifyAndConfirm(PaymentTransaction tx) {
        if (!GATEWAY.equals(tx.getGatewayType())) {
            // A Stripe event naming another gateway's transaction: ignored, never confirmed.
            log.warn("Stripe verification refused for transaction {} of gateway {}", tx.getTransactionId(), tx.getGatewayType());
            return tx.getStatus();
        }
        if ("COMPLETED".equals(tx.getStatus()) || "REJECTED".equals(tx.getStatus())) {
            return tx.getStatus();
        }
        if (tx.getTransactionId() != null && tx.getTransactionId().startsWith(UNKNOWN_OUTCOME)) {
            return tx.getStatus(); // audit 2026-10-26: its PaymentIntent is not known yet; its webhook names it
        }
        try {
            Map<String, Object> credentials = yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                    "Bearer " + tokenService.getServiceAccountToken(), tx.getInvoiceId(), "STRIPE");
            Object apiKey = credentials == null ? null : credentials.get("apiKey");
            if (apiKey == null) {
                throw new IllegalStateException("Stripe credentials missing for invoice " + tx.getInvoiceId());
            }
            return apply(tx, retrieveSession(apiKey.toString(), tx.getTransactionId()));
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe API error: " + e.getMessage(), e);
        }
    }

    /** Applies the state read at Stripe to the transaction and confirms it to YoSales. */
    String apply(PaymentTransaction tx, SessionState state) {
        if (state.paid()) {
            if (!"COMPLETED".equals(tx.getStatus())) {
                tx.setStatus("VERIFIED");
                paymentTransactionRepository.save(tx);
            }
            confirmToYoSales(tx, "SUCCESS", state);
        } else if ("expired".equals(state.status())) {
            tx.setStatus("FAILED");
            tx.setFailureReason(state.failureCode() != null ? state.failureCode() : "Stripe session expired");
            paymentTransactionRepository.save(tx);
            confirmToYoSales(tx, "FAILED", state);
        } else {
            log.info("Stripe payment {} still {} / {}", tx.getTransactionId(), state.status(), state.paymentStatus());
        }
        return tx.getStatus();
    }

    void confirmToYoSales(PaymentTransaction tx, String status, Long amountMinorUnits, String currency) {
        confirmToYoSales(tx, status, new SessionState(null, null, amountMinorUnits, currency));
    }

    /** Sends the verified result to YoSales (SUB-10); YoSales checks amount and currency and ignores a repeat. */
    void confirmToYoSales(PaymentTransaction tx, String status, SessionState state) {
        String currency = state.currency();
        PaymentConfirmationRequest body = new PaymentConfirmationRequest(tx.getInvoiceId(), tx.getPaymentId(),
                tx.getTransactionId(), status, state.amountTotal(), currency == null ? tx.getCurrency() : currency.toUpperCase());
        if ("SUCCESS".equals(status) && state.card() != null && state.card().paymentMethodRef() != null) {
            body.setCard(state.card().toDto()); // SUB-62: kept by YoSales for the automatic renewals
        }
        if ("FAILED".equals(status)) {
            body.setFailureCode(state.failureCode());
        }
        try {
            Map<String, String> answer = yosalesFeign.confirmPayment("Bearer " + tokenService.getServiceAccountToken(), body);
            log.info("YoSales answer for Stripe payment {} ({}): {}", tx.getTransactionId(), status, answer);
            if ("SUCCESS".equals(status)) {
                tx.setStatus("COMPLETED");
                tx.setCompletedAt(java.time.LocalDateTime.now());
                paymentTransactionRepository.save(tx);
            }
        } catch (feign.FeignException.Conflict refused) {
            log.error("YoSales refused the confirmation of Stripe payment {}: {}", tx.getTransactionId(), refused.contentUTF8());
            if ("SUCCESS".equals(status)) {
                tx.setStatus("REJECTED");
                tx.setFailureReason("YoSales refused the confirmation: " + refused.contentUTF8());
                paymentTransactionRepository.save(tx);
            }
        } catch (Exception e) {
            log.error("Could not send the confirmation of Stripe payment {} to YoSales, it will be sent again",
                    tx.getTransactionId(), e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SUB-62: saved card
    // ---------------------------------------------------------------------------------------------------------------

    /** A payment by link keeps the card at Stripe (same Stripe customer for every payment of the coach). */
    static void saveCardFor(SessionCreateParams.Builder session, PaymentRequestDto request) {
        if (!Boolean.TRUE.equals(request.getSaveCard())) {
            return;
        }
        if (request.getProviderCustomerId() != null && !request.getProviderCustomerId().isBlank()) {
            session.setCustomer(request.getProviderCustomerId());
        } else {
            session.setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS);
        }
        session.setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                .setSetupFutureUsage(SessionCreateParams.PaymentIntentData.SetupFutureUsage.OFF_SESSION)
                .build());
    }

    /** Result of a charge of a saved card at Stripe: the PaymentIntent (null when Stripe refused before) and its state. */
    public record OffSessionCharge(String paymentIntentId, SessionState state) {
    }

    /**
     * SUB-62: charges the saved card without the coach. One charge per YoSales payment (same answer when asked again,
     * and the idempotency key of Stripe makes sure the card is never charged twice). The result goes to YoSales by the
     * single confirmation path, here and by the webhook / the reconciliation if this answer is lost.
     */
    @Override
    public PaymentResponseDto chargeSavedCard(PaymentRequestDto request) throws Exception {
        validateRequest(request);
        if (request.getProviderCustomerId() == null || request.getProviderPaymentMethodId() == null) {
            throw new IllegalArgumentException("No saved card for this payment");
        }
        Optional<PaymentTransaction> already = paymentTransactionRepository.findByPaymentId(request.getPaymentId());
        if (already.isPresent()) {
            PaymentTransaction tx = already.get();
            return new PaymentResponseDto(tx.getTransactionId(), null, tx.getStatus(), tx.getFailureReason());
        }
        long amount = amountInMinorUnits(request);
        String currency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("paymentId", String.valueOf(request.getPaymentId()));
        metadata.put("invoiceId", String.valueOf(request.getInvoiceId()));
        metadata.put("userId", request.getUserId());
        String description = request.getDescription() != null ? request.getDescription() : "Invoice #" + request.getInvoiceId();

        // Review 2026-10-26: charged with the key of the invoice's product, read from YoSales. A key sent by the caller
        // (its own Stripe account) must never turn into a confirmed YoSales invoice.
        String productKey = productKeyOfInvoice(request.getInvoiceId());
        if (!productKey.equals(request.getApiKey())) {
            throw new IllegalArgumentException("The Stripe key is not the one of the product of invoice " + request.getInvoiceId());
        }
        OffSessionCharge charge;
        try {
            charge = chargeAtStripe(productKey, amount, currency.toLowerCase(), request.getProviderCustomerId(),
                    request.getProviderPaymentMethodId(), description, metadata, "yosales-payment-" + request.getPaymentId());
        } catch (com.stripe.exception.ApiConnectionException | com.stripe.exception.ApiException unknownOutcome) {
            // Audit 2026-10-26: no answer, or an error of Stripe itself, after the charge was sent: the card may have been
            // charged. Kept PENDING (never "refused", which made YoSales charge it again): its webhook
            // (payment_intent.*, found by the paymentId of its metadata) confirms the result.
            log.error("Stripe gave no clear answer for the saved card of payment {}: waiting for its webhook", request.getPaymentId(), unknownOutcome);
            paymentTransactionRepository.save(PaymentTransaction.builder()
                    .paymentId(request.getPaymentId())
                    .transactionId(UNKNOWN_OUTCOME + request.getPaymentId())
                    .gatewayType(GATEWAY)
                    .status("PENDING")
                    .amount(BigDecimal.valueOf(amount, 2))
                    .amountMillimes(amount)
                    .currency(currency)
                    .description(description)
                    .invoiceId(request.getInvoiceId())
                    .customerId(request.getUserId())
                    .build());
            return new PaymentResponseDto(null, null, "PENDING", "Stripe did not answer: the result comes with its webhook");
        } catch (StripeException e) {
            log.error("Stripe could not charge the saved card for payment {}", request.getPaymentId(), e);
            throw new Exception("Error charging the saved card: " + e.getMessage(), e);
        }
        PaymentTransaction tx = paymentTransactionRepository.save(PaymentTransaction.builder()
                .paymentId(request.getPaymentId())
                .transactionId(charge.paymentIntentId() != null ? charge.paymentIntentId() : "declined-" + request.getPaymentId())
                .gatewayType(GATEWAY)
                .status("PENDING")
                .amount(BigDecimal.valueOf(amount, 2))
                .amountMillimes(amount)
                .currency(currency)
                .description(description)
                .invoiceId(request.getInvoiceId())
                .customerId(request.getUserId())
                .build());
        String status = apply(tx, charge.state());
        log.info("Saved card charged for payment {} (invoice {}): {}", request.getPaymentId(), request.getInvoiceId(), status);
        return new PaymentResponseDto(tx.getTransactionId(), null, status, tx.getFailureReason());
    }

    /** The Stripe secret key of the product of the invoice, as YoSales stores it. */
    private String productKeyOfInvoice(Long invoiceId) {
        Map<String, Object> credentials = yosalesFeign.getBillingInfoByInvoiceIdAndGateway(
                "Bearer " + tokenService.getServiceAccountToken(), invoiceId, GATEWAY);
        Object apiKey = credentials == null ? null : credentials.get("apiKey");
        if (apiKey == null || apiKey.toString().isBlank()) {
            throw new IllegalStateException("Stripe credentials missing for invoice " + invoiceId);
        }
        return apiKey.toString();
    }

    /** Creates and confirms the PaymentIntent of a saved card at Stripe (overridden in tests). */
    protected OffSessionCharge chargeAtStripe(String apiKey, long amount, String currency, String customer, String paymentMethod,
                                              String description, Map<String, String> metadata, String idempotencyKey)
            throws StripeException {
        RequestOptions options = RequestOptions.builder().setApiKey(apiKey).setIdempotencyKey(idempotencyKey).build();
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(currency)
                .setCustomer(customer)
                .setPaymentMethod(paymentMethod)
                .setOffSession(true)
                .setConfirm(true)
                .setDescription(description)
                .putAllMetadata(metadata)
                .addExpand("payment_method")
                .build();
        try {
            PaymentIntent pi = PaymentIntent.create(params, options);
            return new OffSessionCharge(pi.getId(), stateOf(pi));
        } catch (CardException declined) {
            PaymentIntent pi = declined.getStripeError() == null ? null : declined.getStripeError().getPaymentIntent();
            String code = declined.getDeclineCode() != null ? declined.getDeclineCode() : declined.getCode();
            log.warn("Saved card refused by Stripe: {}", code);
            return new OffSessionCharge(pi == null ? null : pi.getId(),
                    new SessionState("expired", "unpaid", amount, currency, null, code == null ? "card_declined" : code));
        }
    }

    /** A Checkout Session started at Stripe. */
    public record StartedSession(String id, String url) {
    }

    /**
     * SUB-62: "change my card": a Stripe page (mode setup, no money moves) where the coach saves a new card. Once
     * Stripe says it is complete, the new card is sent to YoSales and used for the next payments.
     */
    @Override
    public PaymentResponseDto setupCard(PaymentRequestDto request) throws Exception {
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new IllegalArgumentException("Stripe secret key is required");
        }
        requireKeyOfMode(request.getApiKey());
        if (request.getSubscriptionId() == null || request.getProductId() == null) {
            throw new IllegalArgumentException("subscriptionId and productId are required");
        }
        String successUrl = request.getSuccesUrl() != null && !request.getSuccesUrl().isBlank() ? request.getSuccesUrl() : "http://localhost:4200";
        String cancelUrl = request.getFailUrl() != null && !request.getFailUrl().isBlank() ? request.getFailUrl() : successUrl;
        try {
            String customer = request.getProviderCustomerId();
            if (customer == null || customer.isBlank()) {
                customer = createCustomer(request.getApiKey(), request);
            }
            Map<String, String> metadata = new HashMap<>();
            metadata.put("purpose", "CARD_SETUP");
            metadata.put("subscriptionId", String.valueOf(request.getSubscriptionId()));
            metadata.put("userId", String.valueOf(request.getUserId()));
            StartedSession session = startSetupSession(request.getApiKey(), customer, successUrl, cancelUrl, metadata);
            cardSetups.save(CardSetup.builder()
                    .sessionId(session.id())
                    .subscriptionId(request.getSubscriptionId())
                    .productId(request.getProductId())
                    .customerId(request.getUserId())
                    .status("PENDING")
                    .build());
            log.info("Card setup session {} started for subscription {}", session.id(), request.getSubscriptionId());
            return new PaymentResponseDto(session.id(), session.url(), "PENDING", "Redirect to Stripe to save a card");
        } catch (StripeException e) {
            log.error("Error creating the Stripe card setup session", e);
            throw new Exception("Error creating the Stripe card setup session: " + e.getMessage(), e);
        }
    }

    /** One Stripe customer per coach, created the first time a card is saved (overridden in tests). */
    protected String createCustomer(String apiKey, PaymentRequestDto request) throws StripeException {
        CustomerCreateParams.Builder params = CustomerCreateParams.builder()
                .putMetadata("yosalesCustomerId", String.valueOf(request.getUserId()));
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            params.setEmail(request.getEmail());
        }
        return Customer.create(params.build(), RequestOptions.builder().setApiKey(apiKey).build()).getId();
    }

    /** Starts the Checkout Session of a payment, with the key of the product (overridden in tests). */
    protected StartedSession startCheckoutSession(String apiKey, SessionCreateParams params) throws StripeException {
        Session session = Session.create(params, RequestOptions.builder().setApiKey(apiKey).build());
        return new StartedSession(session.getId(), session.getUrl());
    }

    /** The Stripe account of a key (overridden in tests). */
    protected Account retrieveAccount(String apiKey) throws StripeException {
        return Account.retrieve(RequestOptions.builder().setApiKey(apiKey).build());
    }

    /** Starts the Checkout Session in setup mode (overridden in tests). */
    protected StartedSession startSetupSession(String apiKey, String customer, String successUrl, String cancelUrl,
                                               Map<String, String> metadata) throws StripeException {
        Session session = Session.create(SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SETUP)
                .setCustomer(customer)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .putAllMetadata(metadata)
                .build(), RequestOptions.builder().setApiKey(apiKey).build());
        return new StartedSession(session.getId(), session.getUrl());
    }

    /** SUB-62: once the coach saved the card at Stripe, YoSales keeps it for the subscription. Safe to repeat. */
    String verifyCardSetup(CardSetup setup) {
        if ("COMPLETED".equals(setup.getStatus()) || "REJECTED".equals(setup.getStatus())) {
            return setup.getStatus();
        }
        String token = "Bearer " + tokenService.getServiceAccountToken();
        try {
            Map<String, Object> credentials = yosalesFeign.getCredentialsByProductAndGateway(token, setup.getProductId(), GATEWAY);
            Object apiKey = credentials == null ? null : credentials.get("apiKey");
            if (apiKey == null) {
                throw new IllegalStateException("Stripe credentials missing for product " + setup.getProductId());
            }
            SessionState state = retrieveSession(apiKey.toString(), setup.getSessionId());
            if ("complete".equals(state.status()) && state.card() != null && state.card().paymentMethodRef() != null) {
                try {
                    yosalesFeign.saveCard(token, setup.getSubscriptionId(), state.card().toDto());
                    setup.setStatus("COMPLETED");
                    setup.setCompletedAt(java.time.LocalDateTime.now());
                } catch (feign.FeignException.Conflict refused) {
                    setup.setStatus("REJECTED");
                    setup.setFailureReason("YoSales refused the card: " + refused.contentUTF8());
                    log.error("YoSales refused the card of setup {}: {}", setup.getSessionId(), refused.contentUTF8());
                } catch (RuntimeException e) {
                    // Audit P5: nothing changed here; the webhook fails so that Stripe sends the event again.
                    log.error("Could not send the card of setup {} to YoSales, it will be sent again", setup.getSessionId(), e);
                    throw e;
                }
            } else if ("expired".equals(state.status())) {
                setup.setStatus("FAILED");
                setup.setFailureReason("Stripe session expired");
            }
            cardSetups.save(setup);
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe API error: " + e.getMessage(), e);
        }
        return setup.getStatus();
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

    /** SUB-62: a PaymentIntent of a saved card changed; one of a payment by link is handled by its session. */
    private void paymentIntentEvent(JsonNode eventJson) {
        JsonNode intent = eventJson.get("data").get("object");
        String paymentIntentId = intent.get("id").asText();
        Optional<PaymentTransaction> tx = paymentTransactionRepository.findByTransactionId(paymentIntentId);
        if (tx.isEmpty()) {
            tx = chargeWithUnknownOutcome(intent, paymentIntentId);
        }
        tx.ifPresentOrElse(
                found -> requireDelivered(verifyAndConfirm(found), paymentIntentId),
                () -> log.debug("PaymentIntent {} belongs to a Checkout Session: handled with the session", paymentIntentId));
    }

    /** Audit 2026-10-26: a charge whose answer was lost at the time (see chargeSavedCard) is recognised by its paymentId. */
    static final String UNKNOWN_OUTCOME = "unknown-";

    private Optional<PaymentTransaction> chargeWithUnknownOutcome(JsonNode intent, String paymentIntentId) {
        JsonNode paymentId = intent.path("metadata").path("paymentId");
        if (paymentId.isMissingNode() || !paymentId.asText().matches("\\d+")) {
            return Optional.empty();
        }
        return paymentTransactionRepository.findByPaymentId(Long.valueOf(paymentId.asText()))
                .filter(t -> t.getTransactionId() != null && t.getTransactionId().startsWith(UNKNOWN_OUTCOME))
                .map(t -> {
                    t.setTransactionId(paymentIntentId);
                    log.info("Saved card charge of payment {} found at Stripe: {}", t.getPaymentId(), paymentIntentId);
                    return paymentTransactionRepository.save(t);
                });
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
            requireKeyOfMode(secretKey); // a test key saved on a live service would make every payment fail
        } catch (IllegalArgumentException wrongMode) {
            log.warn("Stripe key refused: {}", wrongMode.getMessage());
            return false;
        }

        try {
            // Audit P6: never set as the key of the whole service (a payment running at the same time would use it).
            Account account = retrieveAccount(secretKey);

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
