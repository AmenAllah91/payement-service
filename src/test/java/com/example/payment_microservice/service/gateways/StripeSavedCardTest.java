package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.CardSetup;
import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.BillingConfigurationDto;
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
    private StripeHandler handler;

    private static final StripeHandler.CardState VISA =
            new StripeHandler.CardState("cus_28", "pm_visa", "visa", "4242", 12L, 2028L);

    @BeforeEach
    void setUp() {
        handler = new StripeHandler(repository, yosales, tokens, new ObjectMapper(), setups) {
            @Override
            protected OffSessionCharge chargeAtStripe(String apiKey, long amount, String currency, String customer,
                                                      String paymentMethod, String description, Map<String, String> metadata,
                                                      String idempotencyKey) {
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

        BillingConfigurationDto config = mock(BillingConfigurationDto.class);
        when(config.getConfigParams()).thenReturn(Map.of("apiKey", "sk_test_product"));
        when(yosales.getBillingInfoByproductIdAndGateway(anyString(), eq("8"), eq("STRIPE"))).thenReturn(config);
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
