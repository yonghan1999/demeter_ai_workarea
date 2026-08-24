package com.demeter.backend.common.error;

public class ServiceNotConfiguredException extends RuntimeException {

    private final String code;

    public ServiceNotConfiguredException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
