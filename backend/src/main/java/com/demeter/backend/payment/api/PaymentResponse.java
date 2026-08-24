package com.demeter.backend.payment.api;

import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.domain.PaymentMethod;
import com.demeter.backend.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        long id,
        long billId,
        String currency,
        BigDecimal amount,
        PaymentMethod method,
        Instant paidAt,
        String referenceNo,
        String note,
        PaymentStatus status,
        Instant reversedAt,
        String reversalReason,
        Instant createdAt,
        BigDecimal billPaidAmount,
        BigDecimal billOutstandingAmount,
        BillStatus billStatus) {

    public static PaymentResponse from(Payment payment, Bill bill) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBillId(),
                "CNY",
                payment.getAmount(),
                payment.getMethod(),
                payment.getPaidAt(),
                payment.getReferenceNo(),
                payment.getNote(),
                payment.getStatus(),
                payment.getReversedAt(),
                payment.getReversalReason(),
                payment.getCreatedAt(),
                bill.getPaidAmount(),
                bill.getOutstandingAmount(),
                bill.getStatus());
    }
}
