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

    @com.fasterxml.jackson.annotation.JsonAlias({"success_link", "successUrl", "successLink"})
    private String succesUrl;

    @com.fasterxml.jackson.annotation.JsonAlias({"fail_link", "failureUrl", "failLink"})
    private String failUrl;

    private String brandName;
    private String brandLogo;
}
