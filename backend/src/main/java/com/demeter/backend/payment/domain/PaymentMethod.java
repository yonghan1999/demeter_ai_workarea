package com.demeter.backend.payment.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum PaymentMethod {
    CASH("cash"),
    BANK_TRANSFER("bank_transfer"),
    WECHAT("wechat"),
    ALIPAY("alipay"),
    OTHER("other");

    private final String value;

    PaymentMethod(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PaymentMethod fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PaymentMethod method : values()) {
            if (method.value.equals(normalized) || method.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unsupported payment method: " + value);
    }
}
