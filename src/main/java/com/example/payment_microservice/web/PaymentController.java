package com.example.payment_microservice.web;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.service.factory.PaymentGatewayFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentGatewayFactory gatewayFactory;

    @Autowired
    public PaymentController(PaymentGatewayFactory gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
    }

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
}
