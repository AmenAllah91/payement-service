package com.example.payment_microservice.dto;

import lombok.Data;

@Data
public class KonnectResponse {
    private String payUrl;
    private String paymentRef;
}
