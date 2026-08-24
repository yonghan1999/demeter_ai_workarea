package com.demeter.backend.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;

record WechatCodeExchangeResponse(
        @JsonProperty("openid") String openId,
        @JsonProperty("unionid") String unionId,
        @JsonProperty("session_key") String sessionKey,
        @JsonProperty("errcode") Integer errorCode,
        @JsonProperty("errmsg") String errorMessage) {
}
