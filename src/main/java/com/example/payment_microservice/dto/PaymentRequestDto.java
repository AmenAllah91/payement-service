package com.example.payment_microservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class PaymentRequestDto {
    /** Legacy: whole dinars. Used only when amountMillimes is absent. */
    private Long amount;
    /** Exact amount in millimes (1 TND = 1000 millimes), sent by YoSales (SUB-10). */
    private Long amountMillimes;
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
    @JsonAlias("successUrl")
    private String succesUrl;
    @JsonAlias("failureUrl")
    private String failUrl;
    private String brandName;
    private String brandLogo;
    /** SUB-62: keep the card at Stripe for the next payments (automatic renewal). */
    private Boolean saveCard;
    /** SUB-62: Stripe customer already known for this subscription (cus_...), to keep one customer per coach. */
    private String providerCustomerId;
    /** SUB-62: saved card to charge without the coach (pm_...). */
    private String providerPaymentMethodId;
    /** SUB-62: subscription whose card is changed (card setup). */
    private Long subscriptionId;
    /** SUB-62: product of the subscription (its Stripe configuration). */
    private Long productId;
}
