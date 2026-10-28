package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.CardSetup;
import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.PaymentConfirmationRequest;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.dto.SavedCardDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.CardSetupRepository;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SUB-62: the card is saved at Stripe, charged without the coach for the renewals, and can be changed. */
class StripeSavedCardTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final CardSetupRepository setups = mock(CardSetupRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private final List<String> idempotencyKeys = new ArrayList<>();
    private StripeHandler.OffSessionCharge atStripe;
    private StripeHandler.SessionState sessionAtStripe;
    private com.stripe.exception.StripeException failure;
    private StripeHandler handler;

    private static final StripeHandler.CardState VISA =
            new StripeHandler.CardState("cus_28", "pm_visa", "visa", "4242", 12L, 2028L);

    @BeforeEach
    void setUp() {
        handler = new StripeHandler(repository, yosales, tokens, new ObjectMapper(), setups) {
            @Override
            protected OffSessionCharge chargeAtStripe(String apiKey, long amount, String currency, String customer,
                                                      String paymentMethod, String description, Map<String, String> metadata,
                                                      String idempotencyKey) throws com.stripe.exception.StripeException {
                if (failure != null) {
                    throw failure;
                }
                assertThat(amount).isEqualTo(1700L);
                assertThat(currency).isEqualTo("usd");
                assertThat(customer).isEqualTo("cus_28");
                assertThat(paymentMethod).isEqualTo("pm_visa");
                idempotencyKeys.add(idempotencyKey);
                return atStripe;
            }

            @Override
            protected SessionState retrieveSession(String apiKey, String id) {
                return sessionAtStripe;
            }

            @Override
            protected String createCustomer(String apiKey, PaymentRequestDto request) {
                return "cus_new";
            }

            @Override
            protected StartedSession startSetupSession(String apiKey, String customer, String successUrl, String cancelUrl,
                                                       Map<String, String> metadata) {
                assertThat(metadata).containsEntry("purpose", "CARD_SETUP");
                return new StartedSession("cs_setup_1", "https://checkout.stripe.test/setup");
            }
        };
        ReflectionTestUtils.setField(handler, "mode", "sandbox");
        when(tokens.getServiceAccountToken()).thenReturn("service-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(setups.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.findByPaymentId(anyLong())).thenReturn(Optional.empty());
        // The key of the product of invoice 91, as YoSales stores it.
        when(yosales.getBillingInfoByInvoiceIdAndGateway("Bearer service-token", 91L, "STRIPE"))
                .thenReturn(Map.of("apiKey", "sk_test_product"));
    }

    /** Review 2026-10-26: a charge with a key that is not the product's (the caller's own Stripe account) is refused. */
    @Test
    void aSavedCardIsNeverChargedWithAKeyOtherThanTheProducts() {
        PaymentRequestDto foreign = renewal();
        foreign.setApiKey("sk_test_attacker");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.chargeSavedCard(foreign))
                .hasMessageContaining("not the one of the product");
        assertThat(idempotencyKeys).isEmpty();
        verify(yosales, never()).confirmPayment(anyString(), any());
    }

    /**
     * Audit 2026-10-26 (L5): Stripe gave no answer after the charge was sent: PENDING (never "refused", which made YoSales
     * charge again), and its webhook, found by the paymentId of its metadata, confirms the result.
     */
    @Test
    void aChargeWithoutAnswerWaitsForItsWebhook() throws Exception {
        failure = new com.stripe.exception.ApiConnectionException("Read timed out");
        List<PaymentTransaction> saved = new ArrayList<>();
        when(repository.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });

        PaymentResponseDto answer = handler.chargeSavedCard(renewal());

        assertThat(answer.getStatus()).isEqualTo("PENDING");
        assertThat(answer.getPaymentId()).isNull(); // YoSales keeps no transaction id: the confirmation sets it
        PaymentTransaction tx = saved.get(0);
        assertThat(tx.getTransactionId()).isEqualTo("unknown-90");
        verify(yosales, never()).confirmPayment(anyString(), any());
        when(repository.findByTransactionId("unknown-90")).thenReturn(Optional.of(tx));
        assertThat(handler.reconcile("unknown-90")).isEqualTo("PENDING"); // nothing asked at Stripe meanwhile

        // The webhook of the PaymentIntent arrives.
        when(repository.findByTransactionId("pi_77")).thenReturn(Optional.empty());
        when(repository.findByPaymentId(90L)).thenReturn(Optional.of(tx));
        when(yosales.getCredentialsByProductAndGateway(anyString(), eq(8L), eq("STRIPE"))).thenReturn(Map.of("webhookSecret", "whsec_x"));
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "CONFIRMED"));
        sessionAtStripe = new StripeHandler.SessionState("complete", "paid", 1700L, "usd");
        String event = "{\"id\":\"evt_9\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                + "{\"id\":\"pi_77\",\"object\":\"payment_intent\",\"metadata\":{\"paymentId\":\"90\",\"invoiceId\":\"91\"}}}}";

        assertThat(handler.handleWebhook(event, sign(event, "whsec_x"), 8L)).isEqualTo(StripeHandler.WebhookOutcome.HANDLED);

        assertThat(tx.getTransactionId()).isEqualTo("pi_77");
        assertThat(tx.getStatus()).isEqualTo("COMPLETED");
        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(anyString(), body.capture());
        assertThat(body.getValue().getPaymentId()).isEqualTo(90L);
        assertThat(body.getValue().getTransactionId()).isEqualTo("pi_77");
    }

    private static String sign(String payload, String secret) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + now + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal((now + "." + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void anUnknownStripeModeStillRequiresLiveKeys() {
        ReflectionTestUtils.setField(handler, "mode", "Live ");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.requireKeyOfMode("sk_test_x"))
                .hasMessageContaining("Live mode");
        ReflectionTestUtils.setField(handler, "mode", "typo");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.requireKeyOfMode("sk_test_x"))
                .hasMessageContaining("Live mode");
        ReflectionTestUtils.setField(handler, "mode", "TEST");
        handler.requireKeyOfMode("sk_test_x");
        assertThat(handler.verifyCredentials(Map.of("apiKey", "sk_live_x"))).isFalse(); // a live key on a test service
    }

    private static PaymentRequestDto renewal() {
        PaymentRequestDto r = new PaymentRequestDto();
        r.setApiKey("sk_test_product");
        r.setPaymentId(90L);
        r.setInvoiceId(91L);
        r.setUserId("28");
        r.setAmountMillimes(1700L);
        r.setCurrency("USD");
        r.setProviderCustomerId("cus_28");
        r.setProviderPaymentMethodId("pm_visa");
        return r;
    }

    @Test
    void aPaymentByLinkKeepsTheCardForTheNextPayments() {
        PaymentRequestDto first = new PaymentRequestDto();
        first.setSaveCard(true);
        SessionCreateParams.Builder b = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("https://app/ok").setCancelUrl("https://app/ko");
        StripeHandler.saveCardFor(b, first);
        SessionCreateParams params = b.build();
        assertThat(params.getCustomerCreation()).isEqualTo(SessionCreateParams.CustomerCreation.ALWAYS);
        assertThat(params.getPaymentIntentData().getSetupFutureUsage())
                .isEqualTo(SessionCreateParams.PaymentIntentData.SetupFutureUsage.OFF_SESSION);

        PaymentRequestDto next = new PaymentRequestDto();
        next.setSaveCard(true);
        next.setProviderCustomerId("cus_28");
        SessionCreateParams.Builder b2 = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("https://app/ok").setCancelUrl("https://app/ko");
        StripeHandler.saveCardFor(b2, next);
        assertThat(b2.build().getCustomer()).isEqualTo("cus_28");
    }

    @Test
    void aPaidLinkSendsTheSavedCardWithTheConfirmation() {
        PaymentTransaction tx = PaymentTransaction.builder().paymentId(44L).transactionId("cs_test_44").invoiceId(47L)
                .gatewayType("STRIPE").status("PENDING").currency("USD").build();
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "CONFIRMED"));

        handler.apply(tx, new StripeHandler.SessionState("complete", "paid", 1700L, "usd", VISA, null));

        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(eq("Bearer service-token"), body.capture());
        SavedCardDto card = body.getValue().getCard();
        assertThat(card.getCustomerRef()).isEqualTo("cus_28");
        assertThat(card.getPaymentMethodRef()).isEqualTo("pm_visa");
        assertThat(card.getLast4()).isEqualTo("4242");
        assertThat(tx.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void theRenewalIsChargedOnTheSavedCardAndConfirmedOnce() throws Exception {
        atStripe = new StripeHandler.OffSessionCharge("pi_90",
                new StripeHandler.SessionState("complete", "paid", 1700L, "usd", VISA, null));
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "CONFIRMED"));

        PaymentResponseDto answer = handler.chargeSavedCard(renewal());

        assertThat(answer.getStatus()).isEqualTo("COMPLETED");
        assertThat(answer.getRedirectUrl()).isNull();
        assertThat(idempotencyKeys).containsExactly("yosales-payment-90");
        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(anyString(), body.capture());
        assertThat(body.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(body.getValue().getAmountMillimes()).isEqualTo(1700L);
        assertThat(body.getValue().getCurrency()).isEqualTo("USD");
        assertThat(body.getValue().getTransactionId()).isEqualTo("pi_90");

        // Asked again for the same payment: the first answer, Stripe is not called again.
        when(repository.findByPaymentId(90L)).thenReturn(Optional.of(PaymentTransaction.builder()
                .paymentId(90L).transactionId("pi_90").status("COMPLETED").build()));
        assertThat(handler.chargeSavedCard(renewal()).getStatus()).isEqualTo("COMPLETED");
        assertThat(idempotencyKeys).hasSize(1);
    }

    @Test
    void aRefusedCardIsAFailureWithItsReasonAndNothingIsPaid() throws Exception {
        atStripe = new StripeHandler.OffSessionCharge("pi_91",
                new StripeHandler.SessionState("expired", "unpaid", 1700L, "usd", null, "insufficient_funds"));
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "FAILURE_RECORDED"));

        PaymentResponseDto answer = handler.chargeSavedCard(renewal());

        assertThat(answer.getStatus()).isEqualTo("FAILED");
        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(anyString(), body.capture());
        assertThat(body.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(body.getValue().getFailureCode()).isEqualTo("insufficient_funds");
        assertThat(body.getValue().getCard()).isNull();
    }

    @Test
    void aCardThatAsksForAnAuthenticationIsAFailureWithoutTheCoach() {
        com.stripe.model.PaymentIntent pi = new com.stripe.model.PaymentIntent();
        pi.setStatus("requires_action");
        pi.setAmount(1700L);
        pi.setCurrency("usd");
        StripeHandler.SessionState state = StripeHandler.stateOf(pi);
        assertThat(state.status()).isEqualTo("expired");
        assertThat(state.failureCode()).isEqualTo("authentication_required");

        pi.setStatus("processing");
        assertThat(StripeHandler.stateOf(pi).status()).isEqualTo("open");
    }

    @Test
    void aNewCardSavedOnTheStripePageIsSentToYoSales() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setApiKey("sk_test_product");
        request.setSubscriptionId(12L);
        request.setProductId(8L);
        request.setUserId("28");
        request.setSuccesUrl("https://app/my-subscription?card=saved");

        PaymentResponseDto answer = handler.setupCard(request);
        assertThat(answer.getRedirectUrl()).isEqualTo("https://checkout.stripe.test/setup");
        ArgumentCaptor<CardSetup> saved = ArgumentCaptor.forClass(CardSetup.class);
        verify(setups).save(saved.capture());
        CardSetup setup = saved.getValue();
        assertThat(setup.getSubscriptionId()).isEqualTo(12L);
        assertThat(setup.getStatus()).isEqualTo("PENDING");

        when(yosales.getCredentialsByProductAndGateway(anyString(), eq(8L), eq("STRIPE")))
                .thenReturn(Map.of("apiKey", "sk_test_product"));
        sessionAtStripe = new StripeHandler.SessionState("open", null, null, null);
        assertThat(handler.verifyCardSetup(setup)).isEqualTo("PENDING");
        verify(yosales, never()).saveCard(anyString(), anyLong(), any());

        sessionAtStripe = new StripeHandler.SessionState("complete", "no_payment_required", 0L, "usd", VISA, null);
        assertThat(handler.verifyCardSetup(setup)).isEqualTo("COMPLETED");
        ArgumentCaptor<SavedCardDto> card = ArgumentCaptor.forClass(SavedCardDto.class);
        verify(yosales).saveCard(eq("Bearer service-token"), eq(12L), card.capture());
        assertThat(card.getValue().getPaymentMethodRef()).isEqualTo("pm_visa");

        // Repeated webhook: nothing more.
        assertThat(handler.verifyCardSetup(setup)).isEqualTo("COMPLETED");
        verify(yosales, times(1)).saveCard(anyString(), anyLong(), any());
    }
}
