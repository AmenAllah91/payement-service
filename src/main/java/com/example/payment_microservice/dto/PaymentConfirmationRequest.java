package com.example.payment_microservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code POST /api/payments/confirmations} on YoSales (SUB-10). Amount verified at the gateway, in millimes. */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentConfirmationRequest {
    private Long invoiceId;
    /** YoSales payment id (PaymentTransaction.paymentId). */
    private Long paymentId;
    private String transactionId;
    /** SUCCESS or FAILED. */
    private String status;
    private Long amountMillimes;
    private String currency;
    /** SUB-62: card saved at Stripe by this payment (kept by YoSales for the automatic renewals); null otherwise. */
    private SavedCardDto card;
    /** SUB-62: why the gateway refused (decline code such as card_declined, expired_card); null otherwise. */
    private String failureCode;

    public PaymentConfirmationRequest(Long invoiceId, Long paymentId, String transactionId, String status,
                                      Long amountMillimes, String currency) {
        this.invoiceId = invoiceId;
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.status = status;
        this.amountMillimes = amountMillimes;
        this.currency = currency;
    }
}
