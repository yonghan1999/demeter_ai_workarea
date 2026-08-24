package com.demeter.backend.common.error;

public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException(String message) {
        super(message);
    }
}
