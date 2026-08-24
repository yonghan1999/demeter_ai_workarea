package com.demeter.backend.common.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.common.error.BusinessRuleException;
import org.junit.jupiter.api.Test;

class PaginationGuardTest {

    private final PaginationProperties properties = new PaginationProperties(100, 1_000, 50);

    @Test
    void acceptsPaginationInsideConfiguredLimits() {
        assertThatCode(() -> PaginationGuard.requireValid(20, 50, properties))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidPageAndSizeValues() {
        assertThatThrownBy(() -> PaginationGuard.requireValid(-1, 20, properties))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("页码");
        assertThatThrownBy(() -> PaginationGuard.requireValid(101, 20, properties))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("页码");
        assertThatThrownBy(() -> PaginationGuard.requireValid(0, 0, properties))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("大小");
        assertThatThrownBy(() -> PaginationGuard.requireValid(0, 51, properties))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("大小");
    }

    @Test
    void rejectsDeepOffsetsEvenWhenPageAndSizeAreIndividuallyAllowed() {
        assertThatThrownBy(() -> PaginationGuard.requireValid(21, 50, properties))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("过深");
    }
}
