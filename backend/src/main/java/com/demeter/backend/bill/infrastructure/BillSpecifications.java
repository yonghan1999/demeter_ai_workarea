package com.demeter.backend.bill.infrastructure;

import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.domain.BillStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.SetJoin;
import jakarta.persistence.criteria.Expression;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class BillSpecifications {

    private BillSpecifications() {
    }

    public static Specification<Bill> filteredBy(
            long tenantId,
            String keyword,
            String code,
            String shipper,
            BillStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String tag) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("tenantId"), tenantId));
            predicates.add(builder.isNull(root.get("deletedAt")));

            if (hasText(keyword)) {
                String pattern = likePattern(keyword);
                SetJoin<Bill, String> tags = root.joinSet("tags", JoinType.LEFT);
                Expression<String> routeWithArrow = builder.concat(
                        builder.concat(root.get("origin"), " → "),
                        root.get("destination"));
                Expression<String> routeWithDao = builder.concat(
                        builder.concat(root.get("origin"), "到"),
                        root.get("destination"));
                query.distinct(true);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("shipper")), pattern, '\\'),
                        builder.like(builder.lower(root.get("code")), pattern, '\\'),
                        builder.like(builder.lower(root.get("vehicleCargo")), pattern, '\\'),
                        builder.like(builder.lower(root.get("origin")), pattern, '\\'),
                        builder.like(builder.lower(root.get("destination")), pattern, '\\'),
                        builder.like(builder.lower(routeWithArrow), pattern, '\\'),
                        builder.like(builder.lower(routeWithDao), pattern, '\\'),
                        builder.like(builder.lower(tags), pattern, '\\')));
            }
            if (hasText(code)) {
                predicates.add(builder.like(builder.lower(root.get("code")), likePattern(code), '\\'));
            }
            if (hasText(shipper)) {
                predicates.add(builder.like(builder.lower(root.get("shipper")), likePattern(shipper), '\\'));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (startDate != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("date"), startDate));
            }
            if (endDate != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("date"), endDate));
            }
            if (hasText(tag)) {
                SetJoin<Bill, String> tags = root.joinSet("tags", JoinType.INNER);
                query.distinct(true);
                predicates.add(builder.equal(tags, tag.trim()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
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
