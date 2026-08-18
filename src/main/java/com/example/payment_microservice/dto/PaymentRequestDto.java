package com.example.payment_microservice.dto;

import lombok.Data;

@Data
public class PaymentRequestDto {
    private Long amount;
    private String currency;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String email;
    private String description;
    private String userId;
    private String gatewayType;
    private String itemName;
    private String clientId;
    private String clientSecret;
    private String apiKey;
    private String walletId;
    private String webhookUrl;
    private String webhookSecret;
    private String webhookId;
    private Long invoiceId;
    private Long paymentId;
    private String succesUrl;
    private String failUrl;
    private String brandName;
    private String brandLogo;
}
