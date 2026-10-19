package com.muslimafree.dashboard.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;

class CursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(1);

    private final String fingerprint = CursorCodec.fingerprint("viewer", 20, 45, "EG");
    private final CursorCodec.Cursor cursor = new CursorCodec.Cursor(fingerprint, NOW, new BigDecimal("812"),
            new BigDecimal("1791288000000000"), UUID.randomUUID());

    @Test
    void roundTrips() {
        assertThat(CursorCodec.decode(CursorCodec.encode(cursor), fingerprint, NOW.plusSeconds(60), TTL))
                .isEqualTo(cursor);
    }

    @Test
    void isUrlSafe() {
        assertThat(CursorCodec.encode(cursor)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void fingerprintDependsOnEveryPart() {
        assertThat(CursorCodec.fingerprint("viewer", 20, 45, "EG")).isEqualTo(fingerprint);
        assertThat(CursorCodec.fingerprint("viewer", 20, 45, "SA")).isNotEqualTo(fingerprint);
        assertThat(CursorCodec.fingerprint("viewer", 20, null, "EG")).isNotEqualTo(fingerprint);
    }

    @Test
    void rejectsACursorOfAnotherSearch() {
        String encoded = CursorCodec.encode(cursor);
        assertInvalid(() -> CursorCodec.decode(encoded, CursorCodec.fingerprint("other"), NOW, TTL));
    }

    @Test
    void rejectsExpiredAndFutureCursors() {
        String encoded = CursorCodec.encode(cursor);
        assertInvalid(() -> CursorCodec.decode(encoded, fingerprint, NOW.plus(TTL).plusSeconds(1), TTL));
        assertInvalid(() -> CursorCodec.decode(encoded, fingerprint, NOW.minusSeconds(1), TTL));
    }

    @Test
    void rejectsGarbage() {
        for (String garbage : new String[] {"", "%%%", "not-base64!", b64("v1|a|b|c|d|e"), b64("v2|" + fingerprint
                + "|1|1|1|" + UUID.randomUUID()), b64("v1|" + fingerprint + "|1|x|1|" + UUID.randomUUID()),
                b64("v1|" + fingerprint + "|1|1|1")}) {
            assertInvalid(() -> CursorCodec.decode(garbage, fingerprint, NOW, TTL));
        }
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CURSOR);
    }
}
