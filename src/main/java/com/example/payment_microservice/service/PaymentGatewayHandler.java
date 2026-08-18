package com.example.payment_microservice.service;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;

public interface PaymentGatewayHandler {
    PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception;
    void handleWebhook(String payload) throws Exception;
}
