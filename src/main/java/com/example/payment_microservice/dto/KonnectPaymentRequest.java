package com.example.payment_microservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class KonnectPaymentRequest {
    private String receiverWalletId;
    private String token;
    private long amount;
    private String type;
    private String description;
    private List<String> acceptedPaymentMethods;
    private int lifespan;
    private boolean checkoutForm;
    private boolean addPaymentFeesToAmount;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String email;
    private String orderId;
    private String webhook;
    private boolean silentWebhook;
    private String successUrl;
    private String failUrl;
    private String theme;
}
