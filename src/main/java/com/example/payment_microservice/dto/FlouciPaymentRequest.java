package com.example.payment_microservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FlouciPaymentRequest {
    private Long amount;

    @JsonProperty("success_link")
    private String successLink;

    @JsonProperty("fail_link")
    private String failLink;

    private String webhook;

    @JsonProperty("developer_tracking_id")
    private String developerTrackingId;
    @JsonProperty("accept_card")
    private boolean acceptCard;

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("image_url")
    private String imageUrl;


}
