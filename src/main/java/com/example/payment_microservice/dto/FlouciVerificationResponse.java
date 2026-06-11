package com.example.payment_microservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FlouciVerificationResponse {
    private boolean success;
    private Result result;

    @JsonProperty("status_code")
    private int statusCode;

    private String name;
    private int code;
    private String version;

    @Data
    public static class Result {
        private String type;
        private Long amount;
        private String status;
        private Details details;

        @JsonProperty("developer_tracking_id")
        private String developerTrackingId;
    }

    @Data
    public static class Details {
        @JsonProperty("order_number")
        private String orderNumber;

        private String name;

        @JsonProperty("approval_code")
        private String approvalCode;

        @JsonProperty("phone_number")
        private String phoneNumber;

        private String email;
    }
}
