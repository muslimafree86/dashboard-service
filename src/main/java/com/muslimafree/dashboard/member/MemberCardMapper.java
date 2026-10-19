package com.muslimafree.dashboard.member;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.discovery.CandidateRow;
import com.muslimafree.dashboard.location.LocationSource;
import com.muslimafree.dashboard.member.photo.PhotoUrlSigner;

/**
 * Builds the cards of one page: the rows from the discovery query plus their photos (one extra query per page). This
 * is the only place that decides what another member's card reveals.
 */
@Component
public class MemberCardMapper {

    private static final int APPROXIMATE_STEP_KM = 5;
    /** The same message key as user-profile-managment, so both services show the same words. */
    static final String PENDING_NAME_KEY = "member.pendingName";
    private static final String APPROVED = "APPROVED";

    private final MemberPhotoRepository photos;
    private final PhotoUrlSigner signer;
    private final MessageSource messages;
    private final Duration onlineWindow;
    private final Clock clock;

    public MemberCardMapper(MemberPhotoRepository photos, PhotoUrlSigner signer, MessageSource messages,
            AppProperties properties, Clock clock) {
        this.photos = photos;
        this.signer = signer;
        this.messages = messages;
        this.onlineWindow = properties.discovery().onlineWindow();
        this.clock = clock;
    }

    /** @param centerApproximate the distances are measured from a city / governorate centre, not a device position */
    public List<MemberCard> toCards(List<CandidateRow> rows, boolean centerApproximate) {
        Map<UUID, List<CardPhoto>> photosByMember = photos.approvedPhotos(rows.stream().map(CandidateRow::id).toList());
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        boolean viewerIsStaff = CurrentUser.isStaff();
        String pendingName = messages.getMessage(PENDING_NAME_KEY, null, LocaleContextHolder.getLocale());
        return rows.stream().map(row -> toCard(row, photosByMember.getOrDefault(row.id(), List.of()), now, today,
                centerApproximate, viewerIsStaff || APPROVED.equals(row.reviewStatus()) ? null : pendingName))
                .toList();
    }

    /**
     * @param pendingName shown instead of the name while staff have not approved the member (user-profile-managment's
     *                    rule: age, place and photos stay visible); null = show the real name
     */
    private MemberCard toCard(CandidateRow row, List<CardPhoto> memberPhotos, Instant now, LocalDate today,
            boolean centerApproximate, String pendingName) {
        boolean approximate = centerApproximate
                || (row.locationSource() != null && LocationSource.valueOf(row.locationSource()).isApproximate());
        Gender gender = row.gender() == null ? null : Gender.valueOf(row.gender());
        return new MemberCard(
                row.id(),
                pendingName == null ? row.firstName() : pendingName,
                pendingName == null ? row.lastName() : "",
                row.dateOfBirth() == null ? null : Period.between(row.dateOfBirth(), today).getYears(),
                new MemberCard.Location(row.countryCode(), row.region(), row.city()),
                new MemberCard.LookingFor(gender == null ? null : gender.opposite(), row.prefAgeMin(), row.prefAgeMax()),
                presence(row, now),
                memberPhotos.isEmpty() ? null : signer.signedUrl(memberPhotos.getFirst()),
                memberPhotos.size(),
                distance(row.distanceKm(), approximate),
                row.distanceKm() == null ? null : approximate);
    }

    /** Identical to the member profile page: online within the window, BUSY if the member chose "appear busy". */
    private MemberCard.Presence presence(CandidateRow row, Instant now) {
        Instant lastActive = row.lastActiveAt() == null ? null : row.lastActiveAt().toInstant(ZoneOffset.UTC);
        boolean recent = lastActive != null && !lastActive.isBefore(now.minus(onlineWindow));
        OnlineStatus status = !recent ? OnlineStatus.OFFLINE
                : "BUSY".equals(row.presenceMode()) ? OnlineStatus.BUSY : OnlineStatus.ONLINE;
        // minute precision is enough for "قبل 10 ساعات" and avoids exposing exact activity timing
        return new MemberCard.Presence(status, lastActive == null ? null : lastActive.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * Whole kilometres, never below 1: "0 km" would say "same grid cell", i.e. pinpoint the member. Between city or
     * governorate centres a kilometre is meaningless, so approximate distances are rounded to 5 km, never below 5.
     */
    private static Integer distance(BigDecimal km, boolean approximate) {
        if (km == null) {
            return null;
        }
        if (approximate) {
            return Math.max(APPROXIMATE_STEP_KM,
                    km.divide(BigDecimal.valueOf(APPROXIMATE_STEP_KM), 0, RoundingMode.HALF_UP).intValue()
                            * APPROXIMATE_STEP_KM);
        }
        return Math.max(1, km.setScale(0, RoundingMode.HALF_UP).intValue());
    }
}
