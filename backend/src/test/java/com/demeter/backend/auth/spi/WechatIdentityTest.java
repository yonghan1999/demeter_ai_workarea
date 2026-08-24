package com.demeter.backend.auth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WechatIdentityTest {

    @Test
    void acceptsWechatIdentifiersWithinThePersistenceBoundary() {
        WechatIdentity identity = new WechatIdentity("openid_A-123", "unionid_B-456");

        assertThat(identity.openId()).isEqualTo("openid_A-123");
        assertThat(identity.unionId()).isEqualTo("unionid_B-456");
    }

    @Test
    void rejectsBlankOversizedOrMalformedIdentifiers() {
        assertThatThrownBy(() -> new WechatIdentity(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openid");
        assertThatThrownBy(() -> new WechatIdentity("a".repeat(129), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openid");
        assertThatThrownBy(() -> new WechatIdentity("valid-openid", "union id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unionid");
    }
}
