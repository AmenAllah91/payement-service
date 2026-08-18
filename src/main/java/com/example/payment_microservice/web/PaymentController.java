package com.example.payment_microservice.web;

import com.example.payment_microservice.domain.PaymentTransaction;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.dto.VerificationRequest;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentGatewayFactory gatewayFactory;


    @PostMapping("/initiate")
    public ResponseEntity<PaymentResponseDto> initiatePayment(@RequestBody PaymentRequestDto request) {
        try {
            PaymentGatewayHandler handler = gatewayFactory.getHandler(request.getGatewayType());
            PaymentResponseDto response = handler.initiatePayment(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new PaymentResponseDto(null, null, "FAILED", e.getMessage()));
        }
    }
    @GetMapping("/{invoiceId}/status-payment")
    public ResponseEntity<Boolean> getStatusPayment(@PathVariable String invoiceId) {
        try {
            PaymentGatewayHandler handler = gatewayFactory.getHandler("FLOUCI");
            Boolean response = handler.getPaymentStatusByInvoiceId(invoiceId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    @PostMapping("/verify-credentials")
    public ResponseEntity<Boolean> verifyCredentials(
            @RequestBody    VerificationRequest verificationRequest) {
        try {
            String gatewayType = verificationRequest.getGatewayType();
            Map<String, Object> credentials = verificationRequest.getConfigParms();
            PaymentGatewayHandler handler = gatewayFactory.getHandler(gatewayType);
            Boolean valid = handler.verifyCredentials(credentials);
            return ResponseEntity.ok(valid);
        } catch (Exception e) {
            return ResponseEntity.ok(false);
        }
    }


}
