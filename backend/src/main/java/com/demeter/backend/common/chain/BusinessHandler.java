package com.demeter.backend.common.chain;

import java.util.Objects;
import java.util.function.Consumer;

public interface BusinessHandler<C extends BusinessContext> {

    String name();

    void handle(C context);

    static <C extends BusinessContext> BusinessHandler<C> named(String name, Consumer<C> action) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Handler name must not be blank");
        }
        return new BusinessHandler<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void handle(C context) {
                action.accept(context);
            }
        };
    }
}
