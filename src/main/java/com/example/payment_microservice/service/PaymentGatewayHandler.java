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

    /**
     * SUB-62: charges the saved card without the coach (automatic renewal, or a payment confirmed by the coach).
     * Answers COMPLETED, PENDING or FAILED; the result is also confirmed to YoSales by the single confirmation path.
     */
    default PaymentResponseDto chargeSavedCard(PaymentRequestDto request) throws Exception {
        throw new UnsupportedOperationException("This payment method cannot charge a saved card");
    }

    /** SUB-62: link to the page where the coach saves a new card (no money moves). */
    default PaymentResponseDto setupCard(PaymentRequestDto request) throws Exception {
        throw new UnsupportedOperationException("This payment method cannot save a card");
    }


}
