package com.demeter.backend.admin.application;

import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.payment.domain.PaymentStatus;

public final class AdminQueryFilters {

    private AdminQueryFilters() {
    }

    public record TenantFilter(String keyword, TenantStatus status) {
    }

    public record UserFilter(Long id, Long tenantId, String keyword, UserStatus status) {
    }

    public record BillFilter(String keyword, Long tenantId, BillStatus status, DeletionStatus deletion) {
        public BillFilter {
            deletion = deletion == null ? DeletionStatus.ALL : deletion;
        }
    }

    public record PaymentFilter(Long id, Long tenantId, Long billId, PaymentStatus status) {
    }

    public record OcrFilter(String taskId, Long tenantId, OcrTaskStatus status, String errorCode) {
    }

    public record AuditFilter(Long tenantId, String action, String aggregateType, String aggregateId) {
    }

    public enum DeletionStatus {
        ACTIVE,
        DELETED,
        ALL
    }
}
