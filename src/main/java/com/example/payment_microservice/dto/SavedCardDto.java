package com.example.payment_microservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SUB-62: the card saved at Stripe, as YoSales keeps it: the references to charge it later and what the coach sees
 * ("Visa •••• 4242, 12/28"). Never the card number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedCardDto {
    /** Stripe customer (cus_...). */
    private String customerRef;
    /** Stripe payment method (pm_...). */
    private String paymentMethodRef;
    private String brand;
    private String last4;
    private Long expMonth;
    private Long expYear;
}
