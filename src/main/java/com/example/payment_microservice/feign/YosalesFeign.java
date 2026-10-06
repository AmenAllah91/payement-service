package com.example.payment_microservice.feign;

import com.example.payment_microservice.dto.BillingConfigurationDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import com.example.payment_microservice.dto.PaymentConfirmationRequest;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "yo-sales", url = "${feign.yosales.uri}")
public interface YosalesFeign {
    @GetMapping("/api/billing-configurations/{productId}/gateway/{paymentGateway}")
    BillingConfigurationDto getBillingInfoByproductIdAndGateway (@RequestHeader("Authorization") String token, @PathVariable("productId") String productId, @PathVariable("paymentGateway") String paymentgateway);
    @GetMapping("/api/billing-configurations/by-invoice/{invoiceId}/{paymentGateway}")
    Map<String,Object> getBillingInfoByInvoiceIdAndGateway (@RequestHeader("Authorization") String token, @PathVariable("invoiceId") Long invoiceId, @PathVariable("paymentGateway") String paymentgateway);
    @PostMapping("/api/invoices/{invoiceId}/mark-payed")
    void markInvoicePaid (@RequestHeader("Authorization") String token, @PathVariable("invoiceId") Long invoiceId);
    @PostMapping("/api/invoices/{invoiceId}/mark-refunded")
    void markInvoiceRefunded(
            @RequestHeader("Authorization") String token,
            @PathVariable("invoiceId") Long invoiceId
    );


    /** SUB-10: confirmation with the amount verified at the gateway (service account only). Idempotent on YoSales. */
    @PostMapping("/api/payments/confirmations")
    Map<String, String> confirmPayment(@RequestHeader("Authorization") String token, @RequestBody PaymentConfirmationRequest body);

    /** SUB-62: the card the coach saved at Stripe ("change my card"), for the subscription (service account only). */
    @PostMapping("/api/subscriptions/{subscriptionId}/card")
    Map<String, Object> saveCard(@RequestHeader("Authorization") String token, @PathVariable("subscriptionId") Long subscriptionId,
                                 @RequestBody com.example.payment_microservice.dto.SavedCardDto card);
}
