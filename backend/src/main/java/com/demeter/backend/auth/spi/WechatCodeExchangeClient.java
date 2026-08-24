package com.demeter.backend.auth.spi;

public interface WechatCodeExchangeClient {

    WechatIdentity exchange(String code);
}
