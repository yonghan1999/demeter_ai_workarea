package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminPropertiesTest {
    @Test
    void rejectsNonPositiveSessionTtl() {
        assertThatThrownBy(() -> new AdminProperties(false, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admin session TTL must be positive");
        assertThatThrownBy(() -> new AdminProperties(false, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admin session TTL must be positive");
    }
}
