package com.demeter.backend.admin.api;

import com.demeter.backend.common.web.PaginationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
class AdminPageRequestFactory {
    private static final int ADMIN_PAGE_SIZE = 30;
    private static final int MAX_QUERY_LENGTH = 120;

    private final PaginationProperties pagination;

    AdminPageRequestFactory(PaginationProperties pagination) {
        this.pagination = pagination;
    }

    PageRequest descending(int page, String sortProperty) {
        return PageRequest.of(
                Math.min(Math.max(0, page), pagination.maxPage() - 1),
                Math.min(ADMIN_PAGE_SIZE, pagination.maxSize()),
                Sort.by(Sort.Direction.DESC, sortProperty).and(Sort.by(Sort.Direction.DESC, "id")));
    }

    String queryText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_QUERY_LENGTH);
    }
}
