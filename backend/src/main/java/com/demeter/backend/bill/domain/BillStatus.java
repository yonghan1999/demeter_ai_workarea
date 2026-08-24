package com.demeter.backend.bill.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum BillStatus {
    UNPAID("unpaid", "未收款"),
    PARTIALLY_PAID("partially_paid", "部分收款"),
    PAID("paid", "已收款");

    private final String value;
    private final String displayName;

    BillStatus(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static BillStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BillStatus status : values()) {
            if (status.value.equals(normalized) || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported bill status: " + value);
    }
}
