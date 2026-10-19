package com.muslimafree.dashboard.report;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveReportRequest(@NotNull ReportStatus status, @Size(max = 1000) String note) {
}
