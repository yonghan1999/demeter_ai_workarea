package com.demeter.backend.auth.infrastructure;

import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import com.demeter.backend.common.error.ExternalServiceException;
import com.demeter.backend.common.error.ServiceNotConfiguredException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class WechatHttpCodeExchangeClient implements WechatCodeExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(WechatHttpCodeExchangeClient.class);

    private final WechatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WechatHttpCodeExchangeClient(
            WechatProperties properties,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            String response = restClient.get()
                    .uri(properties.codeToSessionUrl(), uri -> uri
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(response)) {
                throw new ExternalServiceException("WECHAT_EMPTY_RESPONSE", "WeChat returned an empty response");
            }
            WechatCodeExchangeResponse responseBody = parseResponse(response);
            if (responseBody == null) {
                throw new ExternalServiceException("WECHAT_INVALID_RESPONSE", "WeChat returned an invalid response");
            }
            if (responseBody.errorCode() != null && responseBody.errorCode() != 0) {
                throw new WechatLoginRejectedException(
                        "WeChat rejected the login code (" + responseBody.errorCode() + ")");
            }
            if (!StringUtils.hasText(responseBody.openId())) {
                throw new ExternalServiceException("WECHAT_INVALID_RESPONSE", "WeChat did not return an openid");
            }
            try {
                return new WechatIdentity(responseBody.openId(), responseBody.unionId());
            } catch (IllegalArgumentException exception) {
                throw new ExternalServiceException(
                        "WECHAT_INVALID_RESPONSE",
                        "WeChat returned an invalid identity",
                        exception);
            }
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn(
                    "WeChat code exchange request failed: exceptionType={}, httpStatus={}, causeType={}",
                    exception.getClass().getSimpleName(),
                    httpStatus(exception),
                    causeType(exception));
            throw new ExternalServiceException(
                    "WECHAT_UNAVAILABLE",
                    "WeChat login service is temporarily unavailable");
        }
    }

    private WechatCodeExchangeResponse parseResponse(String response) {
        try {
            return objectMapper.readValue(response, WechatCodeExchangeResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ExternalServiceException(
                    "WECHAT_INVALID_RESPONSE",
                    "WeChat returned an invalid response");
        }
    }

    private static String httpStatus(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return Integer.toString(responseException.getStatusCode().value());
        }
        return "none";
    }

    private static String causeType(RestClientException exception) {
        Throwable cause = exception.getCause();
        return cause == null ? "none" : cause.getClass().getSimpleName();
    }
}
