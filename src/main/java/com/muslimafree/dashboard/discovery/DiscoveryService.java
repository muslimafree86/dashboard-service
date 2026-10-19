package com.muslimafree.dashboard.discovery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.util.CursorCodec;
import com.muslimafree.dashboard.location.GeoPoint;
import com.muslimafree.dashboard.location.LocationIndexer;
import com.muslimafree.dashboard.location.LocationSource;
import com.muslimafree.dashboard.location.PlaceDirectory;
import com.muslimafree.dashboard.location.PlaceResponse;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.MemberCardMapper;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ProfileSnapshotRepository;
import com.muslimafree.dashboard.member.ViewerService;

/**
 * "Given the signed-in member, which other members are relevant to them, and in which order?"
 *
 * <pre>
 * current user -> own profile + preferences -> eligible members -> filters -> relevance -> order -> page -> cards
 * </pre>
 *
 * Everything from "eligible members" to "page" is one SQL statement ({@link DiscoveryRepository}); this class validates
 * the request, fixes the clock of the scroll session and assembles the cards.
 */
@Service
public class DiscoveryService {

    static final int MIN_AGE = 18;
    static final int MAX_AGE = 99;
    static final int MAX_DISTANCE_KM = 500;
    static final int MAX_PAGE_SIZE = 50;
    /**
     * {@code NEAREST} searches circles of these radii beyond the previous page's last distance, widest last, and stops at
     * the first that fills the page: a dense city is answered from a few km around the viewer, a sparse region looks
     * further. Only when even the widest circle is not enough is every position read.
     */
    static final int[] NEAREST_RINGS_KM = {10, 50, 200, 1000};

    private final ViewerService viewers;
    private final ProfileSnapshotRepository profiles;
    private final DiscoveryRepository discovery;
    private final MemberCardMapper cards;
    private final LocationIndexer locations;
    private final PlaceDirectory places;
    private final AppProperties.Discovery properties;
    private final Clock clock;

    public DiscoveryService(ViewerService viewers, ProfileSnapshotRepository profiles, DiscoveryRepository discovery,
            MemberCardMapper cards, LocationIndexer locations, PlaceDirectory places, AppProperties properties,
            Clock clock) {
        this.viewers = viewers;
        this.profiles = profiles;
        this.discovery = discovery;
        this.cards = cards;
        this.locations = locations;
        this.places = places;
        this.properties = properties.discovery();
        this.clock = clock;
    }

    /** The Home feed and its tabs: الرئيسية (default), متصل الان ({@code online}), الشركاء ({@code mutual}). */
    @Transactional(readOnly = true)
    public DiscoveryPage feed(CurrentUser user, DiscoverySearchRequest request) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        if (request.ageMin() != null && request.ageMax() != null && request.ageMin() > request.ageMax()) {
            throw new ApiException(ErrorCode.INVALID_AGE_RANGE);
        }
        DiscoverySort sort = request.sortOrDefault();
        GeoPoint center = request.near() == null ? centerOf(viewer) : places.find(request.near())
                .map(p -> new GeoPoint(p.latitude(), p.longitude(),
                        p.isRegion() ? LocationSource.REGION : LocationSource.CITY, p.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PLACE));
        if ((request.distanceKm() != null || sort == DiscoverySort.NEAREST) && center == null) {
            throw new ApiException(ErrorCode.LOCATION_REQUIRED);
        }
        String country = StringUtils.hasText(request.country()) ? request.country().toUpperCase(Locale.ROOT) : null;
        String city = StringUtils.hasText(request.city()) ? request.city().strip() : null;

        Instant now = clock.instant();
        String nationality = StringUtils.hasText(request.nationality())
                ? request.nationality().toUpperCase(Locale.ROOT) : null;
        String minEducation = request.educationMin() == null ? null : request.educationMin().name();
        String children = request.children() == null ? null : request.children().name();

        String fingerprint = CursorCodec.fingerprint(viewer.id(), request.ageMin(), request.ageMax(), country, city,
                request.distanceKm(), request.near(), request.onlineOnly(), request.mutualOnly(), sort,
                new TreeSet<>(request.maritalStatusCodes()), minEducation, new TreeSet<>(request.sectCodes()),
                new TreeSet<>(request.smokingCodes()), children, nationality, request.withPhotoOnly());
        CursorCodec.Cursor cursor = request.cursor() == null ? null
                : CursorCodec.decode(request.cursor(), fingerprint, now, properties.cursorTtl());
        Instant asOf = cursor == null ? now.truncatedTo(ChronoUnit.MILLIS) : cursor.asOf();
        LocalDateTime asOfUtc = LocalDateTime.ofInstant(asOf, ZoneOffset.UTC);
        LocalDate today = asOfUtc.toLocalDate();

        DiscoveryQuery.Filters filters = DiscoveryQuery.Filters.builder()
                .born(request.ageMin() == null ? null : today.minusYears(request.ageMin()),
                        request.ageMax() == null ? null : today.minusYears(request.ageMax() + 1L))
                .country(country)
                .city(city)
                .activeSince(request.onlineOnly() ? asOfUtc.minus(properties.onlineWindow()) : null)
                .radiusKm(request.distanceKm())
                .mutualOnly(request.mutualOnly())
                .maritalStatuses(request.maritalStatusCodes())
                .minEducation(minEducation)
                .sects(request.sectCodes())
                .smoking(request.smokingCodes())
                .children(children)
                .nationality(nationality)
                .withPhoto(request.withPhotoOnly())
                .build();
        DiscoveryQuery.Keyset after = cursor == null ? null
                : new DiscoveryQuery.Keyset(cursor.primary(), cursor.secondary(), cursor.id().toString());
        int size = request.pageSize();
        DiscoveryQuery query = new DiscoveryQuery(viewer, center, asOfUtc, candidateGender(viewer),
                properties.requireVerifiedEmail(), filters, sort, after, size + 1, null);
        List<CandidateRow> rows = sort == DiscoverySort.NEAREST ? nearest(query) : discovery.find(query);

        boolean hasMore = rows.size() > size;
        List<CandidateRow> page = hasMore ? rows.subList(0, size) : rows;
        String next = null;
        if (hasMore) {
            CandidateRow last = page.getLast();
            BigDecimal[] key = sortKey(last, sort);
            next = CursorCodec.encode(new CursorCodec.Cursor(fingerprint, asOf, key[0], key[1], last.id()));
        }
        return new DiscoveryPage(cards.toCards(page, approximate(center)), next, hasMore);
    }

    /**
     * {@code NEAREST} page: the closest members within a growing circle. Every member in a circle is closer than every
     * member outside it, so as soon as one circle holds a full page that page is exact. The chosen radius (if any) caps
     * the circles.
     */
    private List<CandidateRow> nearest(DiscoveryQuery query) {
        Integer chosen = query.filters().radiusKm();
        int from = query.after() == null ? 0 : query.after().primary().intValue();
        for (int ring : NEAREST_RINGS_KM) {
            int radius = from + ring;
            boolean last = chosen != null && radius >= chosen;
            List<CandidateRow> rows = discovery.find(withRadius(query, last ? chosen : radius));
            if (last || rows.size() >= query.limit()) {
                return rows;
            }
        }
        return discovery.find(query);
    }

    private static DiscoveryQuery withRadius(DiscoveryQuery q, int radiusKm) {
        return new DiscoveryQuery(q.viewer(), q.center(), q.asOf(), q.candidateGender(), q.requireVerifiedEmail(),
                q.filters().withRadiusKm(radiusKm), q.sort(), q.after(), q.limit(), q.similarTo());
    }

    /**
     * "اعضاء مشابهون" on a member's profile page: members the viewer may see, from the same country and of a similar
     * age as that member, most similar first. 404 when the viewer may not see the member themselves.
     */
    @Transactional(readOnly = true)
    public List<MemberCard> similar(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        LocalDateTime asOf = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Gender gender = candidateGender(viewer);
        ProfileSnapshot reference = isVisible(viewer, memberId) ? profiles.find(memberId).orElse(null) : null;
        if (reference == null) {
            throw new ApiException(ErrorCode.MEMBER_NOT_FOUND);
        }

        LocalDate today = asOf.toLocalDate();
        Integer age = reference.ageOn(today);
        int span = properties.similar().ageSpan();
        DiscoveryQuery.Filters filters = DiscoveryQuery.Filters.builder()
                .born(age == null ? null : today.minusYears(Math.max(MIN_AGE, age - span)),
                        age == null ? null : today.minusYears(age + span + 1L))
                .country(reference.countryCode())
                .excludeId(memberId)
                .build();
        GeoPoint center = centerOf(viewer);
        List<CandidateRow> rows = discovery.find(new DiscoveryQuery(viewer, center, asOf, gender,
                properties.requireVerifiedEmail(), filters, DiscoverySort.RELEVANCE, null, properties.similar().limit(),
                reference));
        return cards.toCards(rows, approximate(center));
    }

    /**
     * Whether the viewer may see this member at all - the profile service's visibility rule, regardless of whether the
     * viewer marked them "not interested".
     */
    @Transactional(readOnly = true)
    public boolean isVisible(ProfileSnapshot viewer, UUID memberId) {
        return !cardRows(viewer, Set.of(memberId)).isEmpty();
    }

    /** Cards of the given members that the viewer may (still) see, including hidden ones, in no particular order. */
    @Transactional(readOnly = true)
    public List<MemberCard> visibleCards(ProfileSnapshot viewer, Set<UUID> memberIds) {
        return cards.toCards(cardRows(viewer, memberIds), approximate(centerOf(viewer)));
    }

    private List<CandidateRow> cardRows(ProfileSnapshot viewer, Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        DiscoveryQuery.Filters only = DiscoveryQuery.Filters.builder().memberIds(memberIds).includeHidden(true).build();
        return discovery.find(new DiscoveryQuery(viewer, centerOf(viewer),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                candidateGender(viewer), properties.requireVerifiedEmail(), only, DiscoverySort.RELEVANCE, null,
                memberIds.size(), null));
    }

    /** Pre-fills the Home search bar from the member's partner preferences. */
    @Transactional(readOnly = true)
    public SearchDefaults searchDefaults(CurrentUser user) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        ProfileSnapshot.Preferences wanted = viewer.preferences();
        String country = wanted.countryCode() != null ? wanted.countryCode() : viewer.countryCode();
        GeoPoint center = centerOf(viewer);
        return new SearchDefaults(
                candidateGender(viewer),
                wanted.ageMin(),
                wanted.ageMax(),
                country,
                wanted.region(),
                center == null ? null : center.source(),
                center == null || center.placeId() == null ? null
                        : places.find(center.placeId()).map(PlaceResponse::of).orElse(null),
                new SearchDefaults.Limits(MIN_AGE, MAX_AGE, MAX_DISTANCE_KM, MAX_PAGE_SIZE));
    }

    /** Where the viewer's distances are measured from: fresh device location, else city, else governorate centre. */
    public GeoPoint centerOf(ProfileSnapshot viewer) {
        return locations.best(viewer.deviceLatitude(), viewer.deviceLongitude(), viewer.deviceUpdatedAt(),
                viewer.countryCode(), viewer.region(), viewer.city()).orElse(null);
    }

    private static boolean approximate(GeoPoint center) {
        return center != null && center.source().isApproximate();
    }

    private Gender candidateGender(ProfileSnapshot viewer) {
        return viewers.visibleGender(viewer);
    }

    /** The values the next page continues from, in the order {@code DiscoveryQueryBuilder} sorts by. */
    private static BigDecimal[] sortKey(CandidateRow row, DiscoverySort sort) {
        return switch (sort) {
            case RELEVANCE -> new BigDecimal[] {row.score(), row.activeKey()};
            case RECENTLY_ACTIVE -> new BigDecimal[] {row.activeKey(), row.score()};
            case NEAREST -> new BigDecimal[] {row.distanceKm(), row.score()};
            case NEWEST -> new BigDecimal[] {row.createdKey(), row.score()};
        };
    }
}
