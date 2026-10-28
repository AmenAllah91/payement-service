package com.example.payment_microservice.web;

import com.example.payment_microservice.config.PaymentProperties;
import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.FlouciVerificationResponse;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit P5 (2026-10-26): a webhook is handled before it is answered. A payment taken at the gateway that YoSales did
 * not get answers 500 so that the gateway sends the event again; a repeat changes nothing once confirmed.
 */
class WebhookAnswerTest {

    private static final String SECRET = "whsec_test_product";
    private static final String PAID = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"checkout.session.completed\","
            + "\"data\":{\"object\":{\"id\":\"cs_test_44\",\"object\":\"checkout.session\"}}}";

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private final WebClient.Builder webClient = mock(WebClient.Builder.class);
    private final PaymentProperties properties = new PaymentProperties();
    private WebhookController controller;
    private PaymentTransaction stripeTx;

    @BeforeEach
    void setUp() {
        StripeHandler stripe = new StripeHandler(repository, yosales, tokens, new ObjectMapper(), mock(CardSetupRepository.class)) {
            @Override
            protected SessionState retrieveSession(String apiKey, String sessionId) {
                return new SessionState("complete", "paid", 1700L, "usd");
            }
        };
        FlouciHandler flouci = new FlouciHandler(properties, repository, yosales, tokens, new ObjectMapper(), webClient);
        Map<String, PaymentGatewayHandler> beans = Map.of("flouciHandler", flouci, "stripeHandler", stripe);
        controller = new WebhookController(yosales, new PaymentGatewayFactory(beans), repository, tokens);
        when(tokens.getServiceAccountToken()).thenReturn("service-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(yosales.getCredentialsByProductAndGateway(anyString(), eq(8L), eq("STRIPE"))).thenReturn(Map.of("webhookSecret", SECRET));
        when(yosales.getBillingInfoByInvoiceIdAndGateway(anyString(), eq(47L), eq("STRIPE"))).thenReturn(Map.of("apiKey", "sk_test_product"));
        stripeTx = tx("cs_test_44", "STRIPE", "USD");
        when(repository.findByTransactionId("cs_test_44")).thenReturn(Optional.of(stripeTx));
    }

    @Test
    void aPaymentYoSalesDidNotGetIsAnswered500AndConfirmedWhenStripeSendsItAgain() {
        when(yosales.confirmPayment(anyString(), any()))
                .thenThrow(new RuntimeException("YoSales unreachable"))
                .thenReturn(Map.of("outcome", "CONFIRMED"));

        assertThat(stripe(PAID, sign(PAID)).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(stripeTx.getStatus()).isEqualTo("VERIFIED");

        assertThat(stripe(PAID, sign(PAID)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stripeTx.getStatus()).isEqualTo("COMPLETED");

        // Sent once more by Stripe: nothing is asked again.
        assertThat(stripe(PAID, sign(PAID)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(yosales, times(2)).confirmPayment(anyString(), any());
    }

    @Test
    void aForgedOrUnsignedEventIsRefusedAndNothingIsConfirmed() {
        assertThat(stripe(PAID, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stripe(PAID, "t=1,v1=forged").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stripe(PAID, sign(PAID.replace("cs_test_44", "cs_test_45"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(yosales, never()).confirmPayment(anyString(), any());
        assertThat(stripeTx.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void whenTheWebhookSecretCannotBeReadTheEventIsAskedAgain() {
        when(yosales.getCredentialsByProductAndGateway(anyString(), eq(8L), eq("STRIPE"))).thenThrow(new RuntimeException("YoSales down"));

        assertThat(stripe(PAID, sign(PAID)).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(yosales, never()).confirmPayment(anyString(), any());
    }

    @Test
    void anEventWithNothingToDoIsAnswered200() {
        String other = PAID.replace("checkout.session.completed", "customer.created");
        assertThat(stripe(other, sign(other)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(yosales, never()).confirmPayment(anyString(), any());
    }

    @Test
    void aFlouciPaymentYoSalesDidNotGetIsAnswered500() {
        PaymentTransaction flouciTx = tx("flouci-44", "FLOUCI", "TND");
        when(repository.findByTransactionId("flouci-44")).thenReturn(Optional.of(flouciTx));
        when(yosales.getBillingInfoByInvoiceIdAndGateway(anyString(), eq(47L), eq("FLOUCI")))
                .thenReturn(Map.of("appToken", "public", "appSecret", "secret"));
        FlouciVerificationResponse paid = new FlouciVerificationResponse();
        paid.setSuccess(true);
        paid.setResult(new FlouciVerificationResponse.Result());
        paid.getResult().setStatus("SUCCESS");
        paid.getResult().setAmount(6667L);
        atFlouci(paid);
        when(yosales.confirmPayment(anyString(), any()))
                .thenThrow(new RuntimeException("YoSales unreachable"))
                .thenReturn(Map.of("outcome", "CONFIRMED"));
        String event = "{\"details\":{\"order_number\":\"flouci-44\"}}";

        assertThat(controller.handleWebhook(8L, "flouci", event, null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(flouciTx.getStatus()).isEqualTo("VERIFIED");

        assertThat(controller.handleWebhook(8L, "flouci", event, null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(flouciTx.getStatus()).isEqualTo("COMPLETED");
    }

    /** Audit 2026-10-26 (L6): back from Flouci while it cannot be read: the page that asks the status, not "failed". */
    @Test
    void aReturnLinkThatCannotBeReadIsNotAFailure() {
        PaymentTransaction flouciTx = tx("flouci-44", "FLOUCI", "TND");
        when(repository.findByTransactionId("flouci-44")).thenReturn(Optional.of(flouciTx));
        when(yosales.getBillingInfoByInvoiceIdAndGateway(anyString(), eq(47L), eq("FLOUCI"))).thenThrow(new RuntimeException("YoSales down"));

        var answer = controller.handleFlouciRedirect(8L, "flouci-44", "47");

        assertThat(answer.getHeaders().getLocation().toString()).contains("/payment/success?invoiceId=47");
        assertThat(flouciTx.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void aFlouciEventForAnUnknownPaymentIsAnswered200() {
        assertThat(controller.handleWebhook(8L, "flouci", "{\"details\":{\"order_number\":\"nope\"}}", null, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(yosales, never()).confirmPayment(anyString(), any());
    }

    /** What Flouci answers to the verification of a payment. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void atFlouci(FlouciVerificationResponse answer) {
        WebClient client = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec uri = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headers = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec response = mock(WebClient.ResponseSpec.class);
        when(webClient.baseUrl(anyString())).thenReturn(webClient);
        when(webClient.build()).thenReturn(client);
        when(client.get()).thenReturn(uri);
        when(uri.uri(anyString())).thenReturn(headers);
        when(headers.header(anyString(), anyString())).thenReturn(headers);
        when(headers.retrieve()).thenReturn(response);
        when(response.onStatus(any(), any())).thenReturn(response);
        when(response.bodyToMono(FlouciVerificationResponse.class)).thenReturn(Mono.just(answer));
    }

    private org.springframework.http.ResponseEntity<String> stripe(String payload, String signature) {
        return controller.handleWebhook(8L, "stripe", payload, signature, Map.of());
    }

    /** The Stripe-Signature header of a payload signed now with the secret of the product. */
    private static String sign(String payload) {
        try {
            long now = System.currentTimeMillis() / 1000;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(mac.doFinal((now + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + now + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaymentTransaction tx(String id, String gateway, String currency) {
        return PaymentTransaction.builder().id(1L).paymentId(44L).transactionId(id).invoiceId(47L).gatewayType(gateway)
                .status("PENDING").currency(currency).amount(new BigDecimal("17.00")).amountMillimes(1700L).customerId("28").build();
    }
}
