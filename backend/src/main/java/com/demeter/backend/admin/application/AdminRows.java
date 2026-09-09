package com.demeter.backend.admin.application;

import com.demeter.backend.audit.domain.AuditEvent;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.infrastructure.PaymentLedgerDiscrepancy;
import com.demeter.backend.maintenance.domain.MaintenanceRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Explicit, presentation-safe rows for the server-rendered management pages. */
public final class AdminRows {

    private AdminRows() {
    }

    public record TenantRow(Long id, String name, String status, Instant createdAt) {
        public static TenantRow from(Tenant tenant) {
            return new TenantRow(tenant.getId(), tenant.getName(),
                    tenant.getStatus() == null ? null : tenant.getStatus().name(), tenant.getCreatedAt());
        }
    }

    public record UserRow(Long id, Long tenantId, String displayName, String status, String openId) {
        public static UserRow from(UserAccount user) {
            return new UserRow(user.getId(), user.getTenantId(), user.getDisplayName(),
                    user.getStatus() == null ? null : user.getStatus().name(), mask(user.getOpenId()));
        }
    }

    public record BillRow(Long id, Long tenantId, String code, String shipper, LocalDate date,
            BigDecimal amount, String status, Instant deletedAt) {
        public static BillRow from(Bill bill) {
            return new BillRow(bill.getId(), bill.getTenantId(), bill.getCode(), bill.getShipper(),
                    bill.getDate(), bill.getAmount(), bill.getStatus() == null ? null : bill.getStatus().name(),
                    bill.getDeletedAt());
        }
    }

    public record OcrRow(String publicId, Long tenantId, String status, int attemptCount,
            int maxAttempts, String lastErrorCode, String lastErrorMessage, Instant nextAttemptAt,
            Instant createdAt, Instant updatedAt) {
        public static OcrRow from(OcrTask task) {
            return new OcrRow(task.getPublicId(), task.getTenantId(),
                    task.getStatus() == null ? null : task.getStatus().name(), task.getAttemptCount(),
                    task.getMaxAttempts(), task.getLastErrorCode(), task.getLastErrorMessage(), task.getNextAttemptAt(),
                    task.getCreatedAt(), task.getUpdatedAt());
        }
    }

    public record PaymentRow(Long id, Long tenantId, Long billId, BigDecimal amount,
            String method, String status, Instant paidAt, Instant reversedAt, String reversalReason) {
        public static PaymentRow from(Payment payment) {
            return new PaymentRow(payment.getId(), payment.getTenantId(), payment.getBillId(), payment.getAmount(),
                    payment.getMethod() == null ? null : payment.getMethod().name(),
                    payment.getStatus() == null ? null : payment.getStatus().name(), payment.getPaidAt(),
                    payment.getReversedAt(), payment.getReversalReason());
        }
    }

    public record AuditRow(Long id, Instant createdAt, Long tenantId, String action, String aggregateType,
            String aggregateId, Long actorUserId, String requestId) {
        public static AuditRow from(AuditEvent event) {
            return new AuditRow(event.getId(), event.getCreatedAt(), event.getTenantId(), event.getAction(),
                    event.getAggregateType(), event.getAggregateId(), event.getActorUserId(), event.getRequestId());
        }
    }

    public record AuditDetailRow(Long id, Instant createdAt, Long tenantId, String action, String aggregateType,
            String aggregateId, Long actorUserId, String requestId, String details) {
        public static AuditDetailRow from(AuditEvent event) {
            return new AuditDetailRow(event.getId(), event.getCreatedAt(), event.getTenantId(), event.getAction(),
                    event.getAggregateType(), event.getAggregateId(), event.getActorUserId(), event.getRequestId(),
                    event.getDetails());
        }
    }

    public record LedgerDiscrepancyRow(long billId, long tenantId, BigDecimal recordedAmount,
            BigDecimal ledgerAmount, BigDecimal difference) {
        public static LedgerDiscrepancyRow from(PaymentLedgerDiscrepancy item) {
            return new LedgerDiscrepancyRow(item.billId(), item.tenantId(), item.recordedAmount(),
                    item.ledgerAmount(), item.recordedAmount().subtract(item.ledgerAmount()));
        }
    }

    public record BillDetailRow(Long id, Long tenantId, String code, String shipper, String vehicleCargo,
            LocalDate date, String origin, String destination, BigDecimal amount, BigDecimal paidAmount,
            BigDecimal outstandingAmount, String status, LocalDate dueDate, List<String> tags,
            Instant createdAt, Instant updatedAt, Instant deletedAt, Long deletedBy, String deleteReason,
            List<PaymentRow> payments,
            List<AuditRow> auditEvents) {
        public static BillDetailRow from(Bill bill, List<PaymentRow> payments, List<AuditRow> auditEvents) {
            return new BillDetailRow(bill.getId(), bill.getTenantId(), bill.getCode(), bill.getShipper(),
                    bill.getVehicleCargo(), bill.getDate(), bill.getOrigin(), bill.getDestination(), bill.getAmount(),
                    bill.getPaidAmount(), bill.getOutstandingAmount(), bill.getStatus() == null ? null : bill.getStatus().name(),
                    bill.getDueDate(), List.copyOf(bill.getTags()), bill.getCreatedAt(), bill.getUpdatedAt(),
                    bill.getDeletedAt(), bill.getDeletedBy(), bill.getDeleteReason(), List.copyOf(payments),
                    List.copyOf(auditEvents));
        }
    }

    public record MaintenanceRunRow(Long id, String status, Instant startedAt, Instant completedAt,
            Long durationMs, int deletedSessions, int deletedCommandReplays, int deletedOcrRetryCommands,
            int deletedOcrDocuments, int deletedOcrOrphans, int failureCount, String failureSummary) {
        public static MaintenanceRunRow from(MaintenanceRun run) {
            return new MaintenanceRunRow(run.getId(), run.getStatus() == null ? null : run.getStatus().name(),
                    run.getStartedAt(), run.getCompletedAt(), run.getDurationMs(), run.getDeletedSessions(),
                    run.getDeletedCommandReplays(), run.getDeletedOcrRetryCommands(), run.getDeletedOcrDocuments(),
                    run.getDeletedOcrOrphans(), run.getFailureCount(), run.getFailureSummary());
        }
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 8 ? value : value.substring(0, 8) + "…";
    }
}
