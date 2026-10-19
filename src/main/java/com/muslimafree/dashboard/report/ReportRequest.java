package com.muslimafree.dashboard.report;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportRequest(
        @NotNull ReportReason reason,
        @Schema(description = "Where it happened; default PROFILE") ReportContext context,
        @Schema(description = "Optional explanation for the moderators") @Size(max = 1000) String details) {
}
