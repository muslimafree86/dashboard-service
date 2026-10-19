package com.muslimafree.dashboard.settings;

import io.swagger.v3.oas.annotations.media.Schema;

public record DashboardSettings(
        @Schema(description = "My profile views are not recorded, so I never appear in \"who viewed me\". Likes, "
                + "favourites and messages are deliberate and stay visible as usual.") boolean incognito) {
}
