package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.PaymentConfirmationRequest;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SUB-61: a Stripe session is always read at Stripe and confirmed to YoSales by the single confirmation path. */
class StripeConfirmationTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private StripeHandler.SessionState atStripe;
    private StripeHandler handler;
    private PaymentTransaction tx;

    @BeforeEach
    void setUp() {
        handler = new StripeHandler(repository, yosales, tokens, new ObjectMapper(),
                mock(com.example.payment_microservice.repositories.CardSetupRepository.class)) {
            @Override
            protected SessionState retrieveSession(String apiKey, String sessionId) {
                assertThat(apiKey).isEqualTo("sk_test_product");
                return atStripe;
            }
        };
        when(tokens.getServiceAccountToken()).thenReturn("service-token");
        when(yosales.getBillingInfoByInvoiceIdAndGateway("Bearer service-token", 47L, "STRIPE"))
                .thenReturn(Map.of("apiKey", "sk_test_product"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        tx = PaymentTransaction.builder().id(1L).paymentId(44L).transactionId("cs_test_44").invoiceId(47L)
                .gatewayType("STRIPE").status("PENDING").currency("USD").amount(new BigDecimal("17.00"))
                .amountMillimes(1700L).customerId("28").build();
        when(repository.findByTransactionId("cs_test_44")).thenReturn(Optional.of(tx));
    }

    @Test
    void theExactAmountInCentsIsSentToStripe() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setAmount(17L);
        request.setAmountMillimes(1750L);
        assertThat(StripeHandler.amountInMinorUnits(request)).isEqualTo(1750L);
        PaymentRequestDto legacy = new PaymentRequestDto();
        legacy.setAmount(17L);
        assertThat(StripeHandler.amountInMinorUnits(legacy)).isEqualTo(1700L);
    }

    @Test
    void aPaidSessionIsConfirmedWithTheAmountAndCurrencyCollectedByStripe() {
        atStripe = new StripeHandler.SessionState("complete", "paid", 1700L, "usd");
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "CONFIRMED"));

        assertThat(handler.reconcile("cs_test_44")).isEqualTo("COMPLETED");

        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(eq("Bearer service-token"), body.capture());
        assertThat(body.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(body.getValue().getAmountMillimes()).isEqualTo(1700L);
        assertThat(body.getValue().getCurrency()).isEqualTo("USD");
        assertThat(body.getValue().getPaymentId()).isEqualTo(44L);
        verify(yosales, never()).markInvoicePaid(anyString(), any());
    }

    @Test
    void anExpiredSessionIsAFailureAndAnOpenOneChangesNothing() {
        atStripe = new StripeHandler.SessionState("open", "unpaid", 1700L, "usd");
        assertThat(handler.reconcile("cs_test_44")).isEqualTo("PENDING");
        verify(yosales, never()).confirmPayment(anyString(), any());

        atStripe = new StripeHandler.SessionState("expired", "unpaid", 1700L, "usd");
        assertThat(handler.reconcile("cs_test_44")).isEqualTo("FAILED");
        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(anyString(), body.capture());
        assertThat(body.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void aRefusalByYoSalesIsKeptForAPersonAndNeverAskedAgain() {
        atStripe = new StripeHandler.SessionState("complete", "paid", 1700L, "usd");
        when(yosales.confirmPayment(anyString(), any())).thenThrow(new FeignException.Conflict("refused",
                Request.create(Request.HttpMethod.POST, "/api/payments/confirmations", Map.of(), null, StandardCharsets.UTF_8, null),
                "{\"outcome\":\"REJECTED\"}".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThat(handler.reconcile("cs_test_44")).isEqualTo("REJECTED");
        reset(yosales);
        assertThat(handler.reconcile("cs_test_44")).isEqualTo("REJECTED");
        verifyNoInteractions(yosales);
    }

    @Test
    void anUnsignedWebhookIsIgnored() {
        com.example.payment_microservice.dto.BillingConfigurationDto config = mock(com.example.payment_microservice.dto.BillingConfigurationDto.class);
        when(config.getConfigParams()).thenReturn(Map.of("webhookSecret", "whsec_test"));
        when(yosales.getBillingInfoByproductIdAndGateway(anyString(), anyString(), eq("STRIPE"))).thenReturn(config);
        String fakePaid = "{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_44\",\"payment_status\":\"paid\"}}}";

        handler.handleWebhook(fakePaid, null, 8L);
        handler.handleWebhook(fakePaid, "t=1,v1=forged", 8L);

        verify(yosales, never()).confirmPayment(anyString(), any());
        verify(yosales, never()).markInvoicePaid(anyString(), any());
        assertThat(tx.getStatus()).isEqualTo("PENDING");
    }
}
