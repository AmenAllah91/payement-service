package com.example.payment_microservice.service.factory;

import com.example.payment_microservice.service.PaymentGatewayHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class PaymentGatewayFactory {

    private final Map<String, PaymentGatewayHandler> handlers;


    public PaymentGatewayHandler getHandler(String gatewayType) {
        return handlers.entrySet().stream()
                .filter(entry -> entry.getKey().toUpperCase().equals(gatewayType.toUpperCase()+"HANDLER"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported payment gateway: " + gatewayType));
    }
}
