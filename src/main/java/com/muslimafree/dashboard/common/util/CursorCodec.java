package com.muslimafree.dashboard.common.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;

/**
 * Opaque keyset cursor: {@code base64url("v1|fingerprint|asOf|primary|secondary|id")}.
 * <ul>
 * <li>{@code fingerprint} - hash of the viewer and the filters/sort; a cursor only continues the search it came
 * from.</li>
 * <li>{@code asOf} - when the first page was computed; later pages rank with the same clock, so a member's score does
 * not change between pages because time passed.</li>
 * <li>{@code primary, secondary, id} - the sort key of the last member returned.</li>
 * </ul>
 * Tampering with a cursor can only change the caller's own position in their own feed: eligibility is re-applied on
 * every page, so it never reveals anything.
 */
public final class CursorCodec {

    private static final String VERSION = "v1";

    private CursorCodec() {
    }

    public record Cursor(String fingerprint, Instant asOf, BigDecimal primary, BigDecimal secondary, UUID id) {
    }

    public static String encode(Cursor cursor) {
        String raw = String.join("|", VERSION, cursor.fingerprint(), Long.toString(cursor.asOf().toEpochMilli()),
                cursor.primary().toPlainString(), cursor.secondary().toPlainString(), cursor.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws ApiException {@link ErrorCode#INVALID_CURSOR} when the cursor is malformed, belongs to another search, or
     *                      is older than {@code ttl}
     */
    public static Cursor decode(String encoded, String expectedFingerprint, Instant now, Duration ttl) {
        Cursor cursor;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            cursor = new Cursor(parts[1], Instant.ofEpochMilli(Long.parseLong(parts[2])), new BigDecimal(parts[3]),
                    new BigDecimal(parts[4]), UUID.fromString(parts[5]));
        } catch (IllegalArgumentException e) { // also NumberFormatException
            throw invalid();
        }
        if (!MessageDigest.isEqual(cursor.fingerprint().getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.getBytes(StandardCharsets.US_ASCII))
                || cursor.asOf().isAfter(now) || cursor.asOf().plus(ttl).isBefore(now)) {
            throw invalid();
        }
        return cursor;
    }

    /** Short, stable hash of everything that defines a search. */
    public static String fingerprint(Object... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.deepToString(parts).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CURSOR);
    }
}
