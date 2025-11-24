package com.example.payment_microservice.feign;

import com.example.payment_microservice.dto.BillingConfigurationDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

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


}
