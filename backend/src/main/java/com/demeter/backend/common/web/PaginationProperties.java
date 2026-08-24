package com.demeter.backend.common.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.pagination")
public record PaginationProperties(
        @Min(1) @Max(100_000) int maxPage,
        @Min(1) @Max(10_000_000) int maxOffset,
        @Min(1) @Max(500) int maxSize) {

    public PaginationProperties {
        if (maxPage < 1 || maxOffset < 1 || maxSize < 1) {
            throw new IllegalArgumentException("Pagination limits must be positive");
        }
    }
}
