package com.muslimafree.dashboard.interaction;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One page of an Activity list, most recent first. Only members the caller may currently see.")
public record ActivityPage(List<ActivityEntry> items, int page, int size, boolean hasMore) {
}
