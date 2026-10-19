package com.muslimafree.dashboard.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.util.TimeOrderedUuid;
import com.muslimafree.dashboard.hidden.HiddenMemberRepository;

/**
 * Member reports. Reporting files the report for moderators and removes the reported member from the reporter's
 * discovery ("not interested"); blocking stays the member's own choice (user-profile-managment's block). Moderators work
 * through the queue here and act on accounts with user-profile-managment's admin API.
 * <p>
 * The answer never says whether the reported id exists, so the endpoint cannot be used to probe accounts. A member may
 * report someone who has since blocked them (harassment often ends with the harasser blocking).
 */
@Service
public class ReportService {

    static final int MAX_RESULTS = 10_000;

    private final ReportRepository reports;
    private final HiddenMemberRepository hidden;
    private final AppProperties.Reports properties;
    private final Clock clock;

    public ReportService(ReportRepository reports, HiddenMemberRepository hidden, AppProperties properties,
            Clock clock) {
        this.reports = reports;
        this.hidden = hidden;
        this.properties = properties.reports();
        this.clock = clock;
    }

    @Transactional
    public void report(CurrentUser user, UUID memberId, ReportRequest request) {
        Instant instant = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        if (reports.reportsSince(user.userId(), now.minusDays(1)) >= properties.maxPerDay()) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", 3600));
        }
        if (memberId.equals(user.userId()) || !reports.profileExists(memberId)) {
            return;
        }
        reports.insert(TimeOrderedUuid.generate(instant.toEpochMilli()), user.userId(), memberId, request.reason(),
                request.context() == null ? ReportContext.PROFILE : request.context(),
                request.details() == null || request.details().isBlank() ? null : request.details().strip(), now);
        hidden.hide(user.userId(), memberId, now);
    }

    @Transactional(readOnly = true)
    public List<ReportResponse> queue(ReportStatus status, UUID reportedId, int page, int size) {
        if ((long) page * size + size > MAX_RESULTS) {
            throw new ApiException(ErrorCode.PAGE_OUT_OF_RANGE, Map.of("maxResults", MAX_RESULTS));
        }
        return reports.page(status, reportedId, page * size, size);
    }

    @Transactional
    public ReportResponse resolve(CurrentUser moderator, UUID reportId, ResolveReportRequest request) {
        if (request.status() == ReportStatus.OPEN) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (reports.resolve(reportId, request.status(), moderator.userId(), request.note(), now) == 0) {
            throw new ApiException(ErrorCode.REPORT_NOT_FOUND);
        }
        return reports.find(reportId).getFirst();
    }
}
