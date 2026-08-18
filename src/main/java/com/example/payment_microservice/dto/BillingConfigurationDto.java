package com.example.payment_microservice.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingConfigurationDto {

    private Long id;

    private String paymentGateway;

    private Map<String, Object> configParams;

    private boolean active;

    private Long productId;

}

