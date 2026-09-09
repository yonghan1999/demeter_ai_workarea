package com.demeter.backend.admin.api;

import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Component
class AdminOperationFeedback {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationFeedback.class);
    private static final String GENERIC_ERROR = "操作失败，请稍后重试";

    String execute(Runnable operation, RedirectAttributes redirect, String target, String successMessage) {
        try {
            operation.run();
            redirect.addFlashAttribute("message", successMessage);
        } catch (BusinessRuleException | ConflictException | ResourceNotFoundException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Unhandled admin operation failure: redirectTarget={}, type={}",
                    target, exception.getClass().getSimpleName(), exception);
            redirect.addFlashAttribute("error", unexpectedFailureMessage(MDC.get("requestId")));
        }
        return "redirect:" + target;
    }

    private static String unexpectedFailureMessage(String requestId) {
        return requestId == null || requestId.isBlank()
                ? GENERIC_ERROR
                : GENERIC_ERROR + "（requestId: " + requestId + "）";
    }
}
