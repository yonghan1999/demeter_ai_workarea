package com.demeter.backend.bill.api;

import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.domain.BillStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record BillResponse(
        Long id,
        String currency,
        String code,
        String shipper,
        String vehicleCargo,
        LocalDate date,
        String from,
        String to,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal outstandingAmount,
        BillStatus status,
        String statusText,
        String amountLabel,
        LocalDate dueDate,
        Set<String> tags,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static BillResponse from(Bill bill) {
        return new BillResponse(
                bill.getId(),
                "CNY",
                bill.getCode(),
                bill.getShipper(),
                bill.getVehicleCargo(),
                bill.getDate(),
                bill.getOrigin(),
                bill.getDestination(),
                bill.getAmount(),
                bill.getPaidAmount(),
                bill.getOutstandingAmount(),
                bill.getStatus(),
                bill.getStatus().displayName(),
                "账单金额",
                bill.getDueDate(),
                bill.getTags(),
                bill.getVersion(),
                bill.getCreatedAt(),
                bill.getUpdatedAt());
    }
}
