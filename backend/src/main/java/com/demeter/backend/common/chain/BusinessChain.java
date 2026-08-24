package com.demeter.backend.common.chain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record BusinessChain<C extends BusinessContext, R>(
        String name,
        BusinessChainExecutionMode executionMode,
        List<BusinessHandler<C>> handlers,
        Function<C, R> resultFactory) {

    public BusinessChain {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(resultFactory, "resultFactory");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Chain name must not be blank");
        }
        if (handlers.isEmpty()) {
            throw new IllegalArgumentException("Business chain must contain at least one handler");
        }
        handlers = List.copyOf(handlers);
        HashSet<String> names = new HashSet<>();
        for (BusinessHandler<C> handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            if (!names.add(handler.name())) {
                throw new IllegalArgumentException("Duplicate handler name in " + name + ": " + handler.name());
            }
        }
    }

    public static <C extends BusinessContext, R> BusinessChain<C, R> of(
            String name,
            BusinessChainExecutionMode executionMode,
            List<BusinessHandler<C>> handlers,
            Function<C, R> resultFactory) {
        return new BusinessChain<>(name, executionMode, handlers, resultFactory);
    }
}
