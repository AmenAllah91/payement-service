package com.example.payment_microservice.repositories;

import com.example.payment_microservice.domain.CardSetup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardSetupRepository extends JpaRepository<CardSetup, Long> {
    Optional<CardSetup> findBySessionId(String sessionId);
}
