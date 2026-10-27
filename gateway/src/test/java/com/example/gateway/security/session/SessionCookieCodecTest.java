package com.example.gateway.security.session;

import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.support.TestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCookieCodecTest {

    @Test
    void roundTripsPayload() {
        SessionCookieCodec codec = new SessionCookieCodec(List.of(key("k1", TestSupport.KEY_1)));
        ObjectNode payload = SessionCookieCodec.newPayload().put("sid", "s-1").put("rt", "secret-refresh-token");

        String cookie = codec.encode(payload);

        assertThat(cookie).doesNotContain("secret-refresh-token").contains(".");
        assertThat(codec.decode(cookie)).hasValueSatisfying(decoded -> {
            assertThat(decoded.get("sid").asString()).isEqualTo("s-1");
            assertThat(decoded.get("rt").asString()).isEqualTo("secret-refresh-token");
        });
    }

    @Test
    void tamperedCookieIsTreatedAsAbsent() {
        SessionCookieCodec codec = new SessionCookieCodec(List.of(key("k1", TestSupport.KEY_1)));
        String cookie = codec.encode(SessionCookieCodec.newPayload().put("sid", "s-1"));
        String[] parts = cookie.split("\\.");
        char[] ciphertext = parts[3].toCharArray();
        ciphertext[0] = ciphertext[0] == 'A' ? 'B' : 'A';
        parts[3] = new String(ciphertext);

        assertThat(codec.decode(String.join(".", parts))).isEmpty();
        assertThat(codec.decode("not-a-jwe")).isEmpty();
        assertThat(codec.decode("")).isEmpty();
    }

    @Test
    void unknownKeyIdIsTreatedAsAbsent() {
        String cookie = new SessionCookieCodec(List.of(key("old", TestSupport.KEY_1)))
                .encode(SessionCookieCodec.newPayload().put("sid", "s-1"));

        assertThat(new SessionCookieCodec(List.of(key("k2", TestSupport.KEY_2))).decode(cookie)).isEmpty();
    }

    @Test
    void rotationKeepsOldCookiesAndEncryptsWithTheFirstKey() {
        String oldCookie = new SessionCookieCodec(List.of(key("k1", TestSupport.KEY_1)))
                .encode(SessionCookieCodec.newPayload().put("sid", "s-1"));
        SessionCookieCodec rotated = new SessionCookieCodec(List.of(key("k2", TestSupport.KEY_2), key("k1", TestSupport.KEY_1)));

        assertThat(rotated.decode(oldCookie)).isPresent();
        assertThat(rotated.isCurrentKey(oldCookie)).isFalse();
        assertThat(rotated.isCurrentKey(rotated.encode(SessionCookieCodec.newPayload()))).isTrue();
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SessionCookieCodec(List.of(key("k1", shortKey))))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SessionCookieCodec(List.of()))
                .hasMessageContaining("gateway.security.session.keys");
    }

    static GatewaySecurityProperties.Session.Key key(String id, String secret) {
        GatewaySecurityProperties.Session.Key key = new GatewaySecurityProperties.Session.Key();
        key.setId(id);
        key.setSecret(secret);
        return key;
    }
}
