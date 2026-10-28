package com.example.payment_microservice.web;

import com.example.payment_microservice.config.PaymentProperties;
import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.CardSetupRepository;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.TokenService;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;
import com.example.payment_microservice.service.gateways.FlouciHandler;
import com.example.payment_microservice.service.gateways.StripeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 0 (audit P1): only Flouci and Stripe exist. A forged PayPal or Konnect webhook is refused at once, and a
 * handler never verifies nor confirms a transaction created by another gateway.
 */
class GatewayIsolationTest {

    /** The "capture completed" event that used to mark an invoice paid without any check. */
    private static final String FORGED_PAYPAL_CAPTURE = "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"X\","
            + "\"status\":\"COMPLETED\",\"supplementary_data\":{\"related_ids\":{\"order_id\":\"flouci-44\"}}}}";

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private FlouciHandler flouci;
    private StripeHandler stripe;
    private PaymentGatewayFactory factory;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        flouci = new FlouciHandler(mock(PaymentProperties.class), repository, yosales, tokens, new ObjectMapper(),
                mock(WebClient.Builder.class));
        stripe = new StripeHandler(repository, yosales, tokens, new ObjectMapper(), mock(CardSetupRepository.class));
        // The beans Spring finds: one per supported gateway.
        Map<String, PaymentGatewayHandler> beans = Map.of("flouciHandler", flouci, "stripeHandler", stripe);
        factory = new PaymentGatewayFactory(beans);
        controller = new WebhookController(yosales, factory, repository, tokens);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "frontendBaseUrl", "http://localhost:4200");
        when(tokens.getServiceAccountToken()).thenReturn("service-token");
    }

    private static PaymentTransaction tx(String id, String gateway) {
        return PaymentTransaction.builder().id(1L).paymentId(44L).transactionId(id).invoiceId(47L).gatewayType(gateway)
                .status("PENDING").currency("TND").amount(new BigDecimal("6.667")).amountMillimes(6667L).customerId("28").build();
    }

    @Test
    void onlyFlouciAndStripeAreSupported() {
        assertThat(factory.getHandler("FLOUCI")).isSameAs(flouci);
        assertThat(factory.getHandler("stripe")).isSameAs(stripe);
        assertThatThrownBy(() -> factory.getHandler("PAYPAL")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.getHandler("KONNECT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForgedPaypalWebhookIsRefusedAndNothingIsPaid() {
        ResponseEntity<String> answer = controller.handleWebhook(8L, "paypal", FORGED_PAYPAL_CAPTURE, null, Map.of());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(yosales, repository);
    }

    @Test
    void aKonnectWebhookIsRefused() {
        ResponseEntity<String> answer = controller.handleWebhook(8L, "konnect", "payment_ref=abc", null, Map.of());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(yosales, repository);
    }

    @Test
    void flouciNeverVerifiesAStripeTransaction() {
        when(repository.findByTransactionId("cs_test_44")).thenReturn(Optional.of(tx("cs_test_44", "STRIPE")));

        flouci.handleWebhookSync("cs_test_44");

        verify(yosales, never()).confirmPayment(any(), any());
        verify(yosales, never()).getBillingInfoByInvoiceIdAndGateway(any(), any(), any());
    }

    @Test
    void aFlouciReturnLinkNamingAStripeTransactionIsNotASuccess() {
        PaymentTransaction paid = tx("cs_test_44", "STRIPE");
        paid.setStatus("COMPLETED");
        when(repository.findByTransactionId("cs_test_44")).thenReturn(Optional.of(paid));

        ResponseEntity<?> answer = controller.handleFlouciRedirect(8L, "cs_test_44", "47");

        assertThat(answer.getHeaders().getLocation()).hasToString("http://localhost:4200/payment/failed?error=transaction_not_found");
        verify(yosales, never()).confirmPayment(any(), any());
    }

    @Test
    void stripeNeverVerifiesAFlouciTransaction() {
        when(repository.findByTransactionId("flouci-44")).thenReturn(Optional.of(tx("flouci-44", "FLOUCI")));

        assertThat(stripe.reconcile("flouci-44")).isEqualTo("PENDING");

        verify(yosales, never()).confirmPayment(any(), any());
        verify(yosales, never()).getBillingInfoByInvoiceIdAndGateway(any(), any(), any());
    }

    /** Audit P14: the status of a payment is asked to the gateway that created it (it was always Flouci). */
    @Test
    void theStatusOfAPaymentIsAskedToItsOwnGateway() {
        PaymentController payments = new PaymentController(factory, repository,
                new com.example.payment_microservice.security.AllowedClients(""));
        PaymentTransaction paid = tx("cs_test_44", "STRIPE");
        paid.setStatus("COMPLETED");
        when(repository.findByTransactionId("cs_test_44")).thenReturn(Optional.of(paid));
        PaymentTransaction open = tx("flouci-45", "FLOUCI");
        open.setStatus("REJECTED");
        when(repository.findByTransactionId("flouci-45")).thenReturn(Optional.of(open));

        assertThat(payments.getStatusPayment("cs_test_44").getBody()).isTrue();
        assertThat(payments.getStatusPayment("flouci-45").getBody()).isFalse();
        assertThat(payments.getStatusPayment("unknown").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(yosales, never()).confirmPayment(any(), any());
    }
}
