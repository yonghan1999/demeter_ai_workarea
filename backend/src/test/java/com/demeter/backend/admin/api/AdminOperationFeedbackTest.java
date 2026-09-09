package com.demeter.backend.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.common.error.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

@ExtendWith(OutputCaptureExtension.class)
class AdminOperationFeedbackTest {

    private final AdminOperationFeedback feedback = new AdminOperationFeedback();

    @AfterEach
    void clearRequestId() {
        MDC.remove("requestId");
    }

    @Test
    void returnsSuccessFeedback() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String result = feedback.execute(() -> {}, redirect, "/admin/users", "用户已启用");

        assertThat(result).isEqualTo("redirect:/admin/users");
        assertThat(redirect.getFlashAttributes().get("message")).isEqualTo("用户已启用");
    }

    @Test
    void exposesExpectedBusinessFailureWithoutLoggingItAsUnexpected(CapturedOutput output) {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        feedback.execute(() -> {
            throw new BusinessRuleException("状态不允许执行该操作");
        }, redirect, "/admin/users", "用户已启用");

        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo("状态不允许执行该操作");
        assertThat(output).doesNotContain("Unhandled admin operation failure");
    }

    @Test
    void logsUnexpectedFailureAndReturnsItsRequestId(CapturedOutput output) {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        MDC.put("requestId", "admin-request-123");

        feedback.execute(() -> {
            throw new IllegalStateException("database unavailable");
        }, redirect, "/admin/users", "用户已启用");

        assertThat(redirect.getFlashAttributes().get("error"))
                .isEqualTo("操作失败，请稍后重试（requestId: admin-request-123）");
        assertThat(output)
                .contains("Unhandled admin operation failure: redirectTarget=/admin/users, type=IllegalStateException")
                .contains("database unavailable");
    }
}
