package com.demeter.backend.admin.application;

interface AdminIdempotentCommand {
    String operationName();

    String idempotencyKey();

    String requestHash();
}
