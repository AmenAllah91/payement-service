package com.example.payment_microservice.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String paymentId;

    @Column(nullable = false)
    private String gatewayType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private String currency;

    @Column
    private String description;

    @Column
    private String userId;

    @Column
    private String metadata;

    private LocalDateTime createdAt = LocalDateTime.now();
}
