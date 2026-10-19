package com.muslimafree.dashboard.report;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/api/v1/dashboard/reports/{memberId}")
    @Operation(summary = "Report a member to the moderators",
            description = "Always 202, whether or not the member exists. Also hides the member from my discovery.")
    public ResponseEntity<Void> report(@PathVariable UUID memberId, @Valid @RequestBody ReportRequest request) {
        reports.report(CurrentUser.require(), memberId, request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/dashboard/admin/reports")
    @Operation(summary = "Moderation queue (moderators): reports by status, oldest first")
    public List<ReportResponse> queue(@RequestParam(defaultValue = "OPEN") ReportStatus status,
            @RequestParam(required = false) UUID reportedId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return reports.queue(status, reportedId, page, size);
    }

    @PatchMapping("/api/v1/dashboard/admin/reports/{reportId}")
    @Operation(summary = "Close a report (moderators): ACTIONED or DISMISSED, with a note")
    public ReportResponse resolve(@PathVariable UUID reportId, @Valid @RequestBody ResolveReportRequest request) {
        return reports.resolve(CurrentUser.require(), reportId, request);
    }
}
