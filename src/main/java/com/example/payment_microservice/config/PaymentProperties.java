// PaymentProperties.java
package com.example.payment_microservice.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
  private final Stripe stripe = new Stripe();
  private final Paypal paypal = new Paypal();
  private final Konnect konnect = new Konnect();
  private final Flouci flouci = new Flouci();

  @Data public static class Stripe {
    private String secretKey;
    private String webhookSecret;
    private String successUrl;
    private String cancelUrl;
  }
  @Data public static class Paypal {
    private String clientId;
    private String clientSecret;
    private String mode;
    private String successUrl;
    private String cancelUrl;
  }
  @Data public static class Konnect {
    private String baseUrl;
    private String apiKey;
    private String walletId;
    private String successUrl;
    private String failUrl;
  }
  @Data public static class Flouci {
    private String baseUrl = "https://developers.flouci.com/api/v2";
  }
}
