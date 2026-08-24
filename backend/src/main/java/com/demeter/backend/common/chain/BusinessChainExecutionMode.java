package com.demeter.backend.common.chain;

/**
 * Declares the side-effect and transaction boundary expected by a business chain.
 * The application service owns the actual transaction boundary; this value makes
 * the boundary reviewable and observable.
 */
public enum BusinessChainExecutionMode {
    READ_ONLY,
    ATOMIC_DATABASE,
    EXTERNAL_IO,
    SCHEDULED
}
