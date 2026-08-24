package com.demeter.backend.common.error;

/** A configured dependency is temporarily unable to serve the operation. */
public class DependencyUnavailableException extends IllegalStateException {

    private final String code;

    public DependencyUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DependencyUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
