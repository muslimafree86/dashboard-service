package com.muslimafree.dashboard.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(UUID id, boolean fromMe, String body, Instant sentAt) {
}
