package com.example.payment_microservice.service;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;

import java.util.Map;

public interface PaymentGatewayHandler {
    PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception;
    void handleWebhook(String paymentRef) throws Exception;
    Boolean verifyCredentials(Map<String, Object> credentials) throws Exception;
}
