package com.example.payment_microservice.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Flouci payment id is found on the return link whatever way Flouci adds it after YoSales' ?invoiceId=X. */
class FlouciReturnLinkTest {

    @Test
    void paymentIdAsItsOwnParameter() {
        assertThat(WebhookController.flouciPaymentId("abc123", "57")).isEqualTo("abc123");
    }

    @Test
    void paymentIdGluedToTheInvoiceId() {
        assertThat(WebhookController.flouciPaymentId(null, "57?payment_id=abc123")).isEqualTo("abc123");
    }

    @Test
    void noPaymentId() {
        assertThat(WebhookController.flouciPaymentId(null, "57")).isNull();
        assertThat(WebhookController.flouciPaymentId(" ", null)).isNull();
        assertThat(WebhookController.flouciPaymentId(null, "57?payment_id=")).isNull();
    }
}
