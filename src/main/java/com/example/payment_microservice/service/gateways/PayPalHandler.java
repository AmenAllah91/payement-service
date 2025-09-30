package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.dto.PaymentResponseDto;
import com.example.payment_microservice.service.PaymentGatewayHandler;
import com.example.payment_microservice.config.PaymentProperties;
import com.paypal.api.payments.*;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.OAuthTokenCredential;
import com.paypal.base.rest.PayPalRESTException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PayPalHandler implements PaymentGatewayHandler {

    private final PaymentProperties paymentProperties;

    @Override
    public PaymentResponseDto initiatePayment(PaymentRequestDto request) throws Exception {
        try {
            APIContext apiContext = createApiContext(
                    paymentProperties.getPaypal().getClientId(),
                    paymentProperties.getPaypal().getClientSecret(),
                    paymentProperties.getPaypal().getMode()
            );

            String currency = request.getCurrency() != null ? request.getCurrency() : "USD";

            // PayPal attend le total en unité "major" (ex: dollars), format 2 décimales.
            Amount amount = new Amount()
                    .setCurrency(currency)
                    .setTotal(String.format("%.2f", request.getAmount()));

            Transaction transaction = new Transaction();
            transaction.setDescription(request.getDescription());
            transaction.setAmount(amount);

            Payer payer = new Payer().setPaymentMethod("paypal");

            RedirectUrls redirectUrls = new RedirectUrls()
                    .setReturnUrl(paymentProperties.getPaypal().getSuccessUrl())
                    .setCancelUrl(paymentProperties.getPaypal().getCancelUrl());

            Payment payment = new Payment()
                    .setIntent("sale")
                    .setPayer(payer)
                    .setTransactions(List.of(transaction))
                    .setRedirectUrls(redirectUrls);

            Payment created = payment.create(apiContext);
            String approvalUrl = created.getLinks().stream()
                    .filter(l -> "approval_url".equalsIgnoreCase(l.getRel()))
                    .findFirst()
                    .map(Links::getHref)
                    .orElseThrow(() -> new Exception("Approval URL not found in PayPal response."));

            return new PaymentResponseDto(
                    created.getId(),
                    approvalUrl,
                    "PENDING",
                    "Redirect to PayPal Checkout"
            );
        } catch (PayPalRESTException e) {
            throw new Exception("Error creating PayPal payment: " + e.getMessage(), e);
        }
    }

    private APIContext createApiContext(String clientId, String clientSecret, String mode) throws PayPalRESTException {
        OAuthTokenCredential tokenCredential = new OAuthTokenCredential(
                clientId, clientSecret, Map.of("mode", mode)
        );
        String accessToken = tokenCredential.getAccessToken();
        APIContext apiContext = new APIContext(accessToken);
        apiContext.setConfigurationMap(Map.of("mode", mode));
        return apiContext;
    }

    @Override
    public void handleWebhook(String payload) {
        // TODO: impl PayPal Webhook si besoin (validation via transmission-id, certs PayPal, etc.)
    }
}
