package com.demeter.backend.common.web;

import com.demeter.backend.common.error.BusinessRuleException;

/** Validates pagination again in the application layer for non-HTTP callers. */
public final class PaginationGuard {

    private PaginationGuard() {
    }

    public static void requireValid(int page, int size, PaginationProperties properties) {
        if (page < 0 || page > properties.maxPage()) {
            throw new BusinessRuleException("分页页码超出允许范围");
        }
        if (size < 1 || size > properties.maxSize()) {
            throw new BusinessRuleException("分页大小超出允许范围");
        }
        long offset = (long) page * size;
        if (offset > properties.maxOffset()) {
            throw new BusinessRuleException("分页位置过深，请缩小筛选范围后继续查询");
        }
    }
}
