package com.demeter.backend.ocr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OcrWorkerPropertiesTest {

    @Test
    void calculatesCappedExponentialRetryDelays() {
        OcrWorkerProperties properties = new OcrWorkerProperties(
                true,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(3),
                10,
                20);

        assertThat(properties.retryDelayForAttempt(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.retryDelayForAttempt(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelayForAttempt(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.retryDelayForAttempt(4)).isEqualTo(Duration.ofMinutes(3));
        assertThat(properties.retryDelayForAttempt(10)).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void rejectsAnInvalidRetryConfiguration() {
        assertThatThrownBy(() -> new OcrWorkerProperties(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        3,
                        20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum retry delay");
    }
}
