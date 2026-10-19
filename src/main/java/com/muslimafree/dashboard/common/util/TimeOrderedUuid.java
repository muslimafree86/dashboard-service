package com.muslimafree.dashboard.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 version-7 UUIDs (48-bit Unix-millisecond timestamp + 74 random bits), as in user-profile-managment: keys of
 * the append-heavy message table are appended at the end of the InnoDB primary key instead of scattered across it.
 */
public final class TimeOrderedUuid {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeOrderedUuid() {
    }

    public static UUID generate(long epochMillis) {
        long randomA = RANDOM.nextInt() & 0xFFFL;
        long randomB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long mostSignificant = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randomA;
        long leastSignificant = 0x8000_0000_0000_0000L | randomB;
        return new UUID(mostSignificant, leastSignificant);
    }
}
