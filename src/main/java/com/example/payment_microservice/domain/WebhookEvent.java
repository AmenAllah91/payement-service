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
public class WebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String gatewayType;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String payload;

    private LocalDateTime receivedAt = LocalDateTime.now();
}
