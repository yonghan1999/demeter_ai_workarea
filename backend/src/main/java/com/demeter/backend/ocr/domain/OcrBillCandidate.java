package com.demeter.backend.ocr.domain;

import com.demeter.backend.bill.domain.BillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record OcrBillCandidate(
        String externalId,
        String code,
        String shipper,
        String vehicleCargo,
        LocalDate date,
        String from,
        String to,
        BigDecimal amount,
        BillStatus status,
        BigDecimal confidence,
        Map<String, BigDecimal> fieldConfidences) {
}
