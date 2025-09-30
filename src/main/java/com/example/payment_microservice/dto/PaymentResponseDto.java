package com.example.payment_microservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentResponseDto {
    private String paymentId;
    private String redirectUrl;
    private String status;
    private String message;
}
