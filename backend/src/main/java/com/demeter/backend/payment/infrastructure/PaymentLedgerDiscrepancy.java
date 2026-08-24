package com.demeter.backend.payment.infrastructure;

import java.math.BigDecimal;

public record PaymentLedgerDiscrepancy(
        long billId,
        long tenantId,
        BigDecimal recordedAmount,
        BigDecimal ledgerAmount) {
}
