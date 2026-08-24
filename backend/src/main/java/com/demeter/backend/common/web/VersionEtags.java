package com.demeter.backend.common.web;

import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.PreconditionRequiredException;

public final class VersionEtags {

    private VersionEtags() {
    }

    public static String format(long version) {
        return "\"" + version + "\"";
    }

    public static long parseRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new PreconditionRequiredException("If-Match 请求头不能为空");
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long version = Long.parseLong(normalized);
            if (version < 0) {
                throw new NumberFormatException("negative version");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new BusinessRuleException("If-Match 必须是有效的资源版本");
        }
    }
}
