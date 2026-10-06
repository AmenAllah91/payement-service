package com.example.payment_microservice.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SUB-62: a coach saves a new card on the Stripe page ("change my card"). No money moves: once Stripe says the
 * session is complete, the card reference is sent to YoSales for the subscription. The card itself stays at Stripe.
 */
@Entity
@Table(name = "card_setups")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardSetup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stripe Checkout Session id (mode setup). */
    @Column(nullable = false, unique = true)
    private String sessionId;

    @Column(nullable = false)
    private Long subscriptionId;

    /** Product of the subscription: its Stripe configuration holds the secret key. */
    @Column(nullable = false)
    private Long productId;

    /** YoSales customer id. */
    @Column
    private String customerId;

    /** PENDING, COMPLETED, FAILED or REJECTED. */
    @Column(nullable = false)
    private String status;

    @Column
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
