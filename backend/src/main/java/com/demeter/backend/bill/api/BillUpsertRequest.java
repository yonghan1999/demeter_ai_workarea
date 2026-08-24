package com.demeter.backend.bill.api;

import com.demeter.backend.bill.domain.BillStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

public record BillUpsertRequest(
        @NotBlank @Size(max = 120) String shipper,
        @Size(max = 120) String vehicleCargo,
        @NotNull LocalDate date,
        @NotBlank @Size(max = 64) String from,
        @NotBlank @Size(max = 64) String to,
        @NotNull
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "99999999.99")
        @Digits(integer = 8, fraction = 2)
        BigDecimal amount,
        @NotNull BillStatus status,
        LocalDate dueDate,
        @Size(max = 10) Set<@NotBlank @Size(max = 40) String> tags) {
}
