package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.config.PaymentProperties;
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
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** SUB-10 on the payment service side: exact amount, field names, confirmation sent to YoSales. */
class FlouciConfirmationTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final YosalesFeign yosales = mock(YosalesFeign.class);
    private final TokenService tokens = mock(TokenService.class);
    private FlouciHandler handler;
    private PaymentTransaction tx;

    @BeforeEach
    void setUp() {
        handler = new FlouciHandler(mock(PaymentProperties.class), repository, yosales, tokens, new ObjectMapper(),
                mock(WebClient.Builder.class));
        when(tokens.getServiceAccountToken()).thenReturn("service-token");
        tx = PaymentTransaction.builder().id(1L).paymentId(44L).transactionId("flouci-44").invoiceId(47L)
                .gatewayType("FLOUCI").status("VERIFIED").currency("TND").amount(new BigDecimal("6.667"))
                .amountMillimes(6667L).customerId("28").build();
    }

    @Test
    void theExactAmountInMillimesIsSentToFlouci() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setAmount(6L);              // what an old YoSales sends (6.667 truncated)
        request.setAmountMillimes(6667L);   // what YoSales sends now
        assertThat(FlouciHandler.amountInMillimes(request)).isEqualTo(6667L);

        PaymentRequestDto legacy = new PaymentRequestDto();
        legacy.setAmount(10L);
        assertThat(FlouciHandler.amountInMillimes(legacy)).isEqualTo(10000L);
    }

    @Test
    void theReturnLinksSentByYoSalesAreReadAgain() throws Exception {
        PaymentRequestDto request = new ObjectMapper().readValue(
                "{\"successUrl\":\"https://app/ok\",\"failureUrl\":\"https://app/ko\",\"amountMillimes\":6667}",
                PaymentRequestDto.class);
        assertThat(request.getSuccesUrl()).isEqualTo("https://app/ok");
        assertThat(request.getFailUrl()).isEqualTo("https://app/ko");
        assertThat(request.getAmountMillimes()).isEqualTo(6667L);
    }

    @Test
    void aConfirmedPaymentIsCompletedWithTheVerifiedAmount() {
        when(yosales.confirmPayment(anyString(), any())).thenReturn(Map.of("outcome", "CONFIRMED"));

        assertThat(handler.confirmToYoSales(tx, "SUCCESS", 6667L)).isTrue();

        ArgumentCaptor<PaymentConfirmationRequest> body = ArgumentCaptor.forClass(PaymentConfirmationRequest.class);
        verify(yosales).confirmPayment(eq("Bearer service-token"), body.capture());
        assertThat(body.getValue().getAmountMillimes()).isEqualTo(6667L);
        assertThat(body.getValue().getPaymentId()).isEqualTo(44L);
        assertThat(body.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(tx.getStatus()).isEqualTo("COMPLETED");
        verify(yosales, never()).markInvoicePaid(anyString(), any());
    }

    @Test
    void aRefusalByYoSalesIsKeptForAHuman() {
        when(yosales.confirmPayment(anyString(), any())).thenThrow(new FeignException.Conflict("refused",
                Request.create(Request.HttpMethod.POST, "/api/payments/confirmations", Map.of(), null, StandardCharsets.UTF_8, null),
                "{\"outcome\":\"REJECTED\"}".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThat(handler.confirmToYoSales(tx, "SUCCESS", 6000L)).isFalse();
        assertThat(tx.getStatus()).isEqualTo("REJECTED");
        assertThat(tx.getFailureReason()).contains("REJECTED");
    }

    @Test
    void whenYoSalesCannotBeReachedTheTransactionStaysVerifiedToBeSentAgain() {
        when(yosales.confirmPayment(anyString(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(handler.confirmToYoSales(tx, "SUCCESS", 6667L)).isFalse();
        assertThat(tx.getStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void reconcileNeverQueriesAgainACompletedOrRejectedTransaction() throws Exception {
        tx.setStatus("COMPLETED");
        when(repository.findByTransactionId("flouci-44")).thenReturn(java.util.Optional.of(tx));
        assertThat(handler.reconcile("flouci-44")).isEqualTo("COMPLETED");

        tx.setStatus("REJECTED");
        assertThat(handler.reconcile("flouci-44")).isEqualTo("REJECTED");
        verify(yosales, never()).confirmPayment(anyString(), any());
    }

    @Test
    void reconcileOfAnUnknownTransactionIsRefused() {
        when(repository.findByTransactionId("nope")).thenReturn(java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.reconcile("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
