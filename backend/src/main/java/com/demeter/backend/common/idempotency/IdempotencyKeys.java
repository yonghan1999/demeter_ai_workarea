package com.demeter.backend.common.idempotency;

import com.demeter.backend.common.error.BusinessRuleException;
import java.util.regex.Pattern;

/**
 * Canonical validation for client supplied command keys.
 *
 * Keeping the accepted alphabet deliberately small prevents control characters,
 * accidental whitespace and log/header ambiguity from entering persistence.
 */
public final class IdempotencyKeys {

    public static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._~:-]{1," + MAX_LENGTH + "}");

    private IdempotencyKeys() {
    }

    public static String require(String value) {
        String key = value == null ? null : value.trim();
        if (key == null || key.isEmpty()) {
            throw new BusinessRuleException("Idempotency-Key 请求头不能为空");
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new BusinessRuleException(
                    "Idempotency-Key 只能包含字母、数字及 . _ ~ : - 字符，长度不能超过 128 个字符");
        }
        return key;
    }

    public static boolean isValid(String value) {
        return value != null && SAFE_KEY.matcher(value).matches();
    }
}
