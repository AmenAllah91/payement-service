package com.example.payment_microservice.service;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;

import java.util.Map;

public interface PaymentGatewayHandler {
    PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception;
    void handleWebhook(String paymentRef) throws Exception;
    Boolean verifyCredentials(Map<String, Object> credentials) throws Exception;
    Boolean getPaymentStatusByInvoiceId(String invoiceId) throws Exception;

    /**
     * SUB-52: asks the gateway for the real state of a transaction, applies it (confirmation sent to YoSales) and
     * returns the state of the transaction: COMPLETED, FAILED, REJECTED, VERIFIED or PENDING.
     */
    default String reconcile(String transactionId) throws Exception {
        return Boolean.TRUE.equals(getPaymentStatusByInvoiceId(transactionId)) ? "COMPLETED" : "PENDING";
    }


}
