package com.example.payment_microservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FlouciResponse {
    private Result result;
    private String name;
    private int code;
    private String version;

    @Data
    public static class Result {
        private boolean success;

        @JsonProperty("payment_id")
        private String paymentId;

        private String link;

        @JsonProperty("developer_tracking_id")
        private String developerTrackingId;
    }
}
