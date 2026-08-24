package com.demeter.backend.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalValuesTest {

    @Test
    void separatesNullEmptyTextAndConcatenatedValues() {
        assertThat(CanonicalValues.sha256(null, ""))
                .isNotEqualTo(CanonicalValues.sha256("", null));
        assertThat(CanonicalValues.sha256("ab", "c"))
                .isNotEqualTo(CanonicalValues.sha256("a", "bc"));
        assertThat(CanonicalValues.sha256("", ""))
                .isNotEqualTo(CanonicalValues.sha256(""));
    }

    @Test
    void includesCollectionBoundariesAndOrderInTheDigest() {
        String grouped = CanonicalValues.builder()
                .addCollection(List.of("a", "b"))
                .add("c")
                .digest();
        String flattened = CanonicalValues.builder()
                .add("a")
                .addCollection(List.of("b", "c"))
                .digest();

        assertThat(grouped).isNotEqualTo(flattened);
        assertThat(CanonicalValues.builder().addCollection(List.of("a", "b")).digest())
                .isNotEqualTo(CanonicalValues.builder().addCollection(List.of("b", "a")).digest());
        assertThat(CanonicalValues.builder().addCollection(null).digest())
                .isNotEqualTo(CanonicalValues.builder().addCollection(List.of()).digest());
    }
}
