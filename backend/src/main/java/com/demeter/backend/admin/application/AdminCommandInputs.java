package com.demeter.backend.admin.application;

import com.demeter.backend.common.error.BusinessRuleException;

final class AdminCommandInputs {
    private AdminCommandInputs() {
    }

    static String requireReason(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 240) {
            throw new BusinessRuleException(message);
        }
        return value.trim();
    }
}
