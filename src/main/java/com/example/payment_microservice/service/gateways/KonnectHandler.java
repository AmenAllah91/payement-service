package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.dto.KonnectPaymentRequest;
import com.example.payment_microservice.dto.KonnectResponse;
import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.config.PaymentProperties;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;

@Service
public class KonnectHandler implements PaymentGatewayHandler {

    private final PaymentProperties paymentProperties;
    private final WebClient webClient;

    public KonnectHandler(WebClient.Builder builder, PaymentProperties props) {
        this.paymentProperties = props;
        this.webClient = builder.baseUrl(props.getKonnect().getBaseUrl()).build();
    }

    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto req) throws Exception {
        final String endpoint = "payments/init-payment";

        KonnectPaymentRequest body = new KonnectPaymentRequest();
        body.setReceiverWalletId(paymentProperties.getKonnect().getWalletId());
        body.setToken(req.getCurrency() != null ? req.getCurrency() : "TND");
        // Konnect attend souvent les millimes: si req.getAmount() = dinars -> *1000
        body.setAmount((long) (req.getAmount() * 1000));
        body.setType("immediate");
        body.setDescription(req.getDescription());
        body.setAcceptedPaymentMethods(Arrays.asList("wallet", "bank_card", "e-DINAR"));
        body.setLifespan(10);
        body.setCheckoutForm(true);
        body.setAddPaymentFeesToAmount(true);
        body.setFirstName(req.getFirstName());
        body.setLastName(req.getLastName());
        body.setPhoneNumber(req.getPhoneNumber());
        body.setEmail(req.getEmail());
        body.setOrderId(req.getUserId());
        body.setWebhook("https://merchant.tech/api/notification_payment"); // à passer aussi en conf si variable
        body.setSilentWebhook(true);
        body.setSuccessUrl(paymentProperties.getKonnect().getSuccessUrl());
        body.setFailUrl(paymentProperties.getKonnect().getFailUrl());

        KonnectResponse res = webClient.post()
                .uri(endpoint)
                .header("x-api-key", paymentProperties.getKonnect().getApiKey())
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(err -> Mono.error(new RuntimeException("Konnect API error: " + err))))
                .bodyToMono(KonnectResponse.class)
                .block();

        return new PaymentResponseDto(
                res.getPaymentRef(),
                res.getPayUrl(),
                "PENDING",
                "Redirect to Konnect Checkout"
        );
    }

    @Override
    public void handleWebhook(String payload) {
        // TODO: impl Webhook Konnect si nécessaire
    }
}
