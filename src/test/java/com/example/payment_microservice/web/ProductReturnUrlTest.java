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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Audit MP5: after Flouci, the customer comes back to the front of the product of the payment, not always YoCoach's. */
class ProductReturnUrlTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        FlouciHandler flouci = new FlouciHandler(mock(PaymentProperties.class), repository, yosales, tokens, new ObjectMapper(),
                mock(WebClient.Builder.class));
        StripeHandler stripe = new StripeHandler(repository, yosales, tokens, new ObjectMapper(), mock(CardSetupRepository.class));
        Map<String, PaymentGatewayHandler> beans = Map.of("flouciHandler", flouci, "stripeHandler", stripe);
        controller = new WebhookController(yosales, new PaymentGatewayFactory(beans), repository, tokens);
        ReflectionTestUtils.setField(controller, "frontendBaseUrl", "https://app.yocoach.test");
    }

    private PaymentTransaction paidFlouci(String id, String returnUrl) {
        PaymentTransaction tx = PaymentTransaction.builder().id(1L).paymentId(44L).transactionId(id).invoiceId(47L)
                .gatewayType("FLOUCI").status("COMPLETED").currency("TND").amount(new BigDecimal("49.000"))
                .amountMillimes(49000L).customerId("28").returnUrl(returnUrl).build();
        when(repository.findByTransactionId(id)).thenReturn(Optional.of(tx));
        return tx;
    }

    @Test
    void theCustomerOfAnotherProductComesBackToItsOwnFront() {
        paidFlouci("flouci-club", "https://club.example.com");

        ResponseEntity<?> answer = controller.handleFlouciRedirect(9L, "flouci-club", "47");

        assertThat(answer.getHeaders().getLocation()).hasToString("https://club.example.com/payment/success?invoiceId=47");
    }

    /** Review 2026-10-26: the return address is kept only from the allowed callers, and only a clean http(s) address. */
    @Test
    void theReturnAddressIsTrustedOnlyFromAllowedCallers() {
        var handler = org.mockito.Mockito.mock(PaymentGatewayHandler.class);
        var factory = new PaymentGatewayFactory(Map.of("flouciHandler", handler));
        var request = new com.example.payment_microservice.dto.PaymentRequestDto();
        request.setGatewayType("FLOUCI");
        request.setReturnUrl("https://evil.example");

        new PaymentController(factory, repository, new com.example.payment_microservice.security.AllowedClients("")).initiatePayment(request);
        assertThat(request.getReturnUrl()).isNull(); // any signed-in caller: the front of this service

        request.setReturnUrl("https://club.example.com");
        new PaymentController(factory, repository, new com.example.payment_microservice.security.AllowedClients("yo-sales")).initiatePayment(request);
        assertThat(request.getReturnUrl()).isEqualTo("https://club.example.com");

        assertThat(PaymentController.validReturnUrl("javascript:alert(1)")).isNull();
        assertThat(PaymentController.validReturnUrl("https://a.example?next=https://evil.example")).isNull();
        assertThat(PaymentController.validReturnUrl("https://user@evil.example")).isNull();
    }

    @Test
    void withoutAFrontForTheProductTheDefaultAddressIsUsed() {
        paidFlouci("flouci-coach", null);

        ResponseEntity<?> answer = controller.handleFlouciRedirect(8L, "flouci-coach", "47");

        assertThat(answer.getHeaders().getLocation()).hasToString("https://app.yocoach.test/payment/success?invoiceId=47");
    }
}
