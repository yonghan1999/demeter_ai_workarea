package com.demeter.backend.auth.infrastructure;

import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import com.demeter.backend.common.error.ExternalServiceException;
import com.demeter.backend.common.error.ServiceNotConfiguredException;
import java.net.http.HttpClient;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatHttpCodeExchangeClient implements WechatCodeExchangeClient {

    private final WechatProperties properties;
    private final RestClient restClient;

    public WechatHttpCodeExchangeClient(WechatProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    @Override
    @CircuitBreaker(name = "wechatLogin")
    @Bulkhead(name = "wechatLogin", type = Bulkhead.Type.SEMAPHORE)
    public WechatIdentity exchange(String code) {
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
            throw new ServiceNotConfiguredException(
                    "WECHAT_NOT_CONFIGURED",
                    "WeChat login is not configured");
        }
        try {
            WechatCodeExchangeResponse response = restClient.get()
                    .uri(properties.codeToSessionUrl(), uri -> uri
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(WechatCodeExchangeResponse.class);
            if (response == null) {
                throw new ExternalServiceException("WECHAT_EMPTY_RESPONSE", "WeChat returned an empty response");
            }
            if (response.errorCode() != null && response.errorCode() != 0) {
                throw new WechatLoginRejectedException(
                        "WeChat rejected the login code (" + response.errorCode() + ")");
            }
            if (!StringUtils.hasText(response.openId())) {
                throw new ExternalServiceException("WECHAT_INVALID_RESPONSE", "WeChat did not return an openid");
            }
            try {
                return new WechatIdentity(response.openId(), response.unionId());
            } catch (IllegalArgumentException exception) {
                throw new ExternalServiceException(
                        "WECHAT_INVALID_RESPONSE",
                        "WeChat returned an invalid identity",
                        exception);
            }
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ExternalServiceException(
                    "WECHAT_UNAVAILABLE",
                    "WeChat login service is temporarily unavailable");
        }
    }
}
