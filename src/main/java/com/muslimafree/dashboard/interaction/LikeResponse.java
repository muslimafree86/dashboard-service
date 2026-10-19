package com.muslimafree.dashboard.interaction;

import io.swagger.v3.oas.annotations.media.Schema;

public record LikeResponse(@Schema(description = "The member had already liked me: this like made a match") boolean matched) {
}
