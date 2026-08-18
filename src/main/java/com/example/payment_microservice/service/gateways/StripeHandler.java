package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.config.PaymentProperties;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class StripeHandler implements PaymentGatewayHandler {

    private final PaymentProperties paymentProperties;

    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception {
        try {
            Stripe.apiKey = paymentProperties.getStripe().getSecretKey();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(paymentProperties.getStripe().getSuccessUrl())
                    .setCancelUrl(paymentProperties.getStripe().getCancelUrl())
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency(request.getCurrency() != null ? request.getCurrency() : "usd")
                                                    // amount en unité mineure (cents)
                                                    .setUnitAmount((long) (request.getAmount() * 100))
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName(request.getItemName())
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            Session session = Session.create(params);
            return new PaymentResponseDto(
                    session.getId(),
                    session.getUrl(),
                    "PENDING",
                    "Redirect to Stripe Checkout"
            );
        } catch (StripeException e) {
            throw new Exception("Error creating Stripe Checkout session: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ Garder la signature d'origine: on lit l'en-tête "Stripe-Signature"
     *    depuis la requête HTTP courante via RequestContextHolder.
     */
    @Override
    public void handleWebhook(String payload) {
        try {
            String sigHeader = extractStripeSignatureHeader();
            if (sigHeader == null || sigHeader.isEmpty()) {
                // On refuse de traiter si la signature n'est pas présente
                throw new SignatureVerificationException(
                        "Missing Stripe-Signature header", payload
                );
            }

            String webhookSecret = paymentProperties.getStripe().getWebhookSecret();
            Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);

            switch (event.getType()) {
                case "checkout.session.completed" -> onCheckoutSessionCompleted(event);
                case "payment_intent.succeeded"   -> onPaymentIntentSucceeded(event);
                default -> System.out.println("Unhandled event type: " + event.getType());
            }

        } catch (SignatureVerificationException e) {
            System.err.println("Invalid Stripe signature: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error processing Stripe webhook: " + e.getMessage());
        }
    }

    // -------- Helpers

    private String extractStripeSignatureHeader() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        return attrs.getRequest().getHeader("Stripe-Signature");
    }

    private void onCheckoutSessionCompleted(Event event) {
        // Option 1: typé
        var maybeObj = event.getDataObjectDeserializer().getObject();
        if (maybeObj.isPresent() && maybeObj.get() instanceof Session session) {
            System.out.println("Checkout session completed: " + session.getId());
            // TODO: logique métier (activer abonnement, créer facture, etc.)
            return;
        }

        // Option 2: fallback JSON si besoin
        var json = ApiResource.GSON.toJsonTree(maybeObj.orElse(null)).getAsJsonObject();
        String sessionId = json.get("id").getAsString();
        System.out.println("Checkout session completed (json): " + sessionId);
    }

    private void onPaymentIntentSucceeded(Event event) {
        var maybeObj = event.getDataObjectDeserializer().getObject();
        if (maybeObj.isPresent() && maybeObj.get() instanceof PaymentIntent pi) {
            System.out.println("Payment intent succeeded: " + pi.getId());
            // TODO: logique métier (marquer payé, persistance, etc.)
            return;
        }

        var json = ApiResource.GSON.toJsonTree(maybeObj.orElse(null)).getAsJsonObject();
        String paymentIntentId = json.get("id").getAsString();
        System.out.println("Payment intent succeeded (json): " + paymentIntentId);
    }
}
