package com.muslimafree.dashboard.member;

import java.util.UUID;

/** A member's approved photo, as needed to sign its link. */
public record CardPhoto(UUID id, UUID userId, String storageKey) {
}
