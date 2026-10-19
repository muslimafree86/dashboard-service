package com.muslimafree.dashboard.report;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A report as moderators see it. Member details: user-profile-managment's GET /admin/users/{id}.")
public record ReportResponse(
        UUID id,
        @Schema(description = "Absent when the reporter has since deleted their account") UUID reporterId,
        UUID reportedId,
        ReportReason reason,
        ReportContext context,
        String details,
        ReportStatus status,
        Instant createdAt,
        @Schema(description = "Open reports against the same member, this one included") long openReportsAgainstMember,
        UUID reviewedBy,
        Instant reviewedAt,
        String resolutionNote) {
}
