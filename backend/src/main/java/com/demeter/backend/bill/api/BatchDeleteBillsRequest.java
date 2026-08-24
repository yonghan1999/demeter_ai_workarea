package com.demeter.backend.bill.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchDeleteBillsRequest(
        @NotEmpty @Size(max = 100) List<@Valid @NotNull @Positive Long> ids,
        @Size(max = 240) String reason) {
}
