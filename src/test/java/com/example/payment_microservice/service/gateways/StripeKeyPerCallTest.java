package com.example.payment_microservice.service.gateways;

import com.example.payment_microservice.dto.PaymentRequestDto;
import com.example.payment_microservice.feign.YosalesFeign;
import com.example.payment_microservice.repositories.CardSetupRepository;
import com.example.payment_microservice.repositories.PaymentTransactionRepository;
import com.example.payment_microservice.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.model.Account;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit P6: the Stripe key of a product is passed with each call. Before, it was set for the whole service
 * ({@code Stripe.apiKey}): a credentials check or a second product running at the same time could create a payment on
 * another Stripe account.
 */
class StripeKeyPerCallTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final List<String> keysUsed = new ArrayList<>();
    private StripeHandler handler;

    @BeforeEach
    void setUp() {
        Stripe.apiKey = null;
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        handler = new StripeHandler(repository, mock(YosalesFeign.class), mock(TokenService.class), new ObjectMapper(),
                mock(CardSetupRepository.class)) {
            @Override
            protected StartedSession startCheckoutSession(String apiKey, SessionCreateParams params) {
                keysUsed.add(apiKey);
                return new StartedSession("cs_test_1", "https://checkout.stripe.test/1");
            }

            @Override
            protected Account retrieveAccount(String apiKey) {
                keysUsed.add(apiKey);
                Account account = new Account();
                account.setId("acct_1");
                return account;
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "mode", "sandbox"); // test keys below
    }

    @Test
    void aPaymentUsesTheKeyOfItsProductWithoutSettingItForTheWholeService() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setApiKey("sk_test_product_9");
        request.setPaymentId(90L);
        request.setInvoiceId(91L);
        request.setAmountMillimes(1500L);
        request.setCurrency("USD");
        request.setUserId("28");

        var answer = handler.initiatePayment(request);

        assertThat(answer.getRedirectUrl()).isEqualTo("https://checkout.stripe.test/1");
        assertThat(keysUsed).containsExactly("sk_test_product_9");
        assertThat(Stripe.apiKey).isNull();
    }

    @Test
    void aCredentialsCheckNeverChangesTheKeyOfTheService() {
        assertThat(handler.verifyCredentials(Map.of("apiKey", "sk_test_candidate"))).isTrue();

        assertThat(keysUsed).containsExactly("sk_test_candidate");
        assertThat(Stripe.apiKey).isNull();
    }
}
