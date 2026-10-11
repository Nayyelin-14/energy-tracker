package com.energytracker.deviceservice.util;

import java.util.UUID;

/**
 * Generates positive, unique Long identifiers from a random UUID.
 *
 * <p>A UUID carries 128 random bits; we keep the 63 most-significant bits so the
 * value always fits in a positive {@code long} (the sign bit is cleared).
 * Collision probability follows the birthday bound over 2^63 values, which is
 * negligible for any realistic number of devices. As a safety net, callers that
 * persist entities handle duplicate-key errors with a bounded retry.
 */
public final class UniqueIdGenerator {

    private UniqueIdGenerator() {
    }

    public static long nextId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
