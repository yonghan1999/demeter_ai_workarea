package com.demeter.backend.common.chain;

/**
 * Request-scoped mutable state shared by the handlers of one business chain.
 */
public abstract class BusinessContext {

    private boolean halted;

    public final boolean isHalted() {
        return halted;
    }

    public final void halt() {
        halted = true;
    }
}
