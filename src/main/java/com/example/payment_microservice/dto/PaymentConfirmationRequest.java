package com.example.payment_microservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code POST /api/payments/confirmations} on YoSales (SUB-10). Amount verified at the gateway, in millimes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfirmationRequest {
    private Long invoiceId;
    /** YoSales payment id (PaymentTransaction.paymentId). */
    private Long paymentId;
    private String transactionId;
    /** SUCCESS or FAILED. */
    private String status;
    private Long amountMillimes;
    private String currency;
}
