package com.demeter.backend.payment.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PaymentStatus {
    ACTIVE("active"),
    REVERSED("reversed");

    private final String value;

    PaymentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
