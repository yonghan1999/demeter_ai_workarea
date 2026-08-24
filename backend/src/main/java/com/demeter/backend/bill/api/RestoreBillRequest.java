package com.demeter.backend.bill.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RestoreBillRequest(
        @NotBlank @Size(max = 240) String reason) {
}
