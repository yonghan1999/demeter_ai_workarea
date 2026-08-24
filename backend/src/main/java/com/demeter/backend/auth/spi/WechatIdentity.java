package com.demeter.backend.auth.spi;

import java.util.regex.Pattern;

public record WechatIdentity(String openId, String unionId) {

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_IDENTIFIER_LENGTH + "}");

    public WechatIdentity {
        openId = requireIdentifier(openId, "openid");
        unionId = unionId == null ? null : requireIdentifier(unionId, "unionid");
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("WeChat " + name + " has an invalid format");
        }
        return value;
    }
}
