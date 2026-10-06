package com.example.payment_microservice.repositories;

import com.example.payment_microservice.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction,Long> {
    Optional<PaymentTransaction> findByTransactionId(String transactionId);
    /** SUB-62: one transaction per YoSales payment (a repeated charge of a saved card returns the first one). */
    Optional<PaymentTransaction> findByPaymentId(Long paymentId);
    Optional<PaymentTransaction> findPaymentTransactionByInvoiceId(Long invoiceId);
}
