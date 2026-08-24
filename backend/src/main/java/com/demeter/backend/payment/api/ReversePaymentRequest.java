package com.demeter.backend.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversePaymentRequest(
        @NotBlank @Size(max = 240) String reason) {
}
