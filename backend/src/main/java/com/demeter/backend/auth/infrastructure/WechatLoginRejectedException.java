package com.demeter.backend.auth.infrastructure;

import com.demeter.backend.common.error.ExternalServiceException;

public class WechatLoginRejectedException extends ExternalServiceException {

    public WechatLoginRejectedException(String message) {
        super("WECHAT_LOGIN_REJECTED", message);
    }
}
