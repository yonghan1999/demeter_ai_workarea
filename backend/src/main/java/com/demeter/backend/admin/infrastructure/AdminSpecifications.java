package com.demeter.backend.admin.infrastructure;

import com.demeter.backend.admin.application.AdminQueryFilters.AuditFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.BillFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.DeletionStatus;
import com.demeter.backend.admin.application.AdminQueryFilters.OcrFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.PaymentFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.TenantFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.UserFilter;
import com.demeter.backend.audit.domain.AuditEvent;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.payment.domain.Payment;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class AdminSpecifications {

    private AdminSpecifications() {
    }

    public static Specification<Tenant> tenants(TenantFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(filter.keyword())) {
                String pattern = likePattern(filter.keyword());
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern, '\\'),
                        builder.like(builder.lower(root.get("publicId")), pattern, '\\')));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<UserAccount> users(UserFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIfPresent(predicates, builder, root.get("id"), filter.id());
            equalIfPresent(predicates, builder, root.get("tenantId"), filter.tenantId());
            if (hasText(filter.keyword())) {
                String pattern = likePattern(filter.keyword());
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("displayName")), pattern, '\\'),
                        builder.like(builder.lower(root.get("openId")), pattern, '\\')));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Bill> bills(BillFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIfPresent(predicates, builder, root.get("tenantId"), filter.tenantId());
            if (hasText(filter.keyword())) {
                String pattern = likePattern(filter.keyword());
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("code")), pattern, '\\'),
                        builder.like(builder.lower(root.get("shipper")), pattern, '\\')));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.deletion() == DeletionStatus.ACTIVE) {
                predicates.add(builder.isNull(root.get("deletedAt")));
            } else if (filter.deletion() == DeletionStatus.DELETED) {
                predicates.add(builder.isNotNull(root.get("deletedAt")));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Payment> payments(PaymentFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIfPresent(predicates, builder, root.get("id"), filter.id());
            equalIfPresent(predicates, builder, root.get("tenantId"), filter.tenantId());
            equalIfPresent(predicates, builder, root.get("billId"), filter.billId());
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<OcrTask> ocrTasks(OcrFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIfPresent(predicates, builder, root.get("tenantId"), filter.tenantId());
            if (hasText(filter.taskId())) {
                predicates.add(builder.like(builder.lower(root.get("publicId")), likePattern(filter.taskId()), '\\'));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (hasText(filter.errorCode())) {
                predicates.add(builder.like(builder.lower(root.get("lastErrorCode")), likePattern(filter.errorCode()), '\\'));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<AuditEvent> auditEvents(AuditFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIfPresent(predicates, builder, root.get("tenantId"), filter.tenantId());
            containsIfPresent(predicates, builder, root.get("action"), filter.action());
            containsIfPresent(predicates, builder, root.get("aggregateType"), filter.aggregateType());
            containsIfPresent(predicates, builder, root.get("aggregateId"), filter.aggregateId());
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void equalIfPresent(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder builder,
            jakarta.persistence.criteria.Path<Object> path, Object value) {
        if (value != null) {
            predicates.add(builder.equal(path, value));
        }
    }

    private static void containsIfPresent(List<Predicate> predicates,
            jakarta.persistence.criteria.CriteriaBuilder builder, jakarta.persistence.criteria.Path<String> path,
            String value) {
        if (hasText(value)) {
            predicates.add(builder.like(builder.lower(path), likePattern(value), '\\'));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String likePattern(String value) {
        String escaped = value.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
