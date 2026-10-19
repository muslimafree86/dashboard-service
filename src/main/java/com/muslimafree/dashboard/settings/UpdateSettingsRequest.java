package com.muslimafree.dashboard.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateSettingsRequest(
        @Schema(description = "Browse without appearing in other members' \"who viewed me\"") @NotNull Boolean incognito) {
}
