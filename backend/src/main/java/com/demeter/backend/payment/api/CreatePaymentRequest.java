package com.demeter.backend.payment.api;

import com.demeter.backend.payment.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record CreatePaymentRequest(
        @NotNull
        @DecimalMin("0.01")
        @DecimalMax("99999999.99")
        @Digits(integer = 8, fraction = 2)
        BigDecimal amount,
        @NotNull PaymentMethod method,
        Instant paidAt,
        @Size(max = 120) String referenceNo,
        @Size(max = 240) String note) {
}
