package com.muslimafree.dashboard.discovery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import com.muslimafree.dashboard.location.GeoPoint;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.ProfileSnapshot;

/**
 * Everything one discovery query depends on. Built by the service after validation; turned into SQL by
 * {@link DiscoveryQueryBuilder}.
 *
 * @param viewer          the signed-in member, whose preferences and traits drive the relevance score
 * @param center          where distances are measured from (the viewer's position or a chosen place), or {@code null}
 * @param asOf            the instant ages, "online now" and activity are computed for; kept by the cursor so that every
 *                        page of one scroll session ranks members identically
 * @param candidateGender {@code null} when members of both genders may be shown
 * @param filters         hard filters chosen in the search bar / tab
 * @param sort            order; ignored when {@link #similarTo} is set
 * @param after           keyset position: the sort key of the last member of the previous page, or {@code null}
 * @param limit           rows to fetch (one more than the page size, to know whether a next page exists)
 */
public record DiscoveryQuery(
        ProfileSnapshot viewer,
        GeoPoint center,
        LocalDateTime asOf,
        Gender candidateGender,
        boolean requireVerifiedEmail,
        Filters filters,
        DiscoverySort sort,
        Keyset after,
        int limit,
        ProfileSnapshot similarTo) {

    /**
     * Hard filters. Every attribute filter is strict: a member who has not filled the attribute in does not match it.
     *
     * @param bornOnOrBefore  age from: candidates at least this old (date of birth on or before)
     * @param bornAfter       age to: candidates younger than this bound (date of birth strictly after)
     * @param city            matched against the member's city or governorate ({@code region}), as the cards show both
     * @param activeSince     "online now": last activity at or after this instant
     * @param radiusKm        "علي مسافة": only members whose shown (rounded) distance from the centre is at most this
     * @param mutualOnly      "الشركاء": no preference contradicted, in either direction
     * @param maritalStatuses any of these marital statuses
     * @param minEducation    at least this education level
     * @param sects           any of these sects
     * @param smoking         any of these smoking habits
     * @param withPhoto       only members with at least one approved photo
     * @param memberIds       restrict to these members (visibility checks, the hidden-members list)
     * @param excludeId       leave one member out (the member a "similar" strip is built for)
     * @param includeHidden   also members the viewer hid ("not interested"); only for visibility checks and the list
     * @param notLiked        leave out members the viewer already liked (daily picks)
     * @param notPickedSince  leave out members picked for the viewer on or after this day (daily picks), or null
     */
    public record Filters(
            LocalDate bornOnOrBefore,
            LocalDate bornAfter,
            String countryCode,
            String city,
            LocalDateTime activeSince,
            Integer radiusKm,
            boolean mutualOnly,
            Set<String> maritalStatuses,
            String minEducation,
            Set<String> sects,
            Set<String> smoking,
            String children,
            String nationality,
            boolean withPhoto,
            Set<UUID> memberIds,
            UUID excludeId,
            boolean includeHidden,
            boolean notLiked,
            LocalDate notPickedSince) {

        public static Builder builder() {
            return new Builder();
        }

        public static Filters none() {
            return builder().build();
        }

        /** The same filters with another radius. */
        public Filters withRadiusKm(Integer value) {
            return new Filters(bornOnOrBefore, bornAfter, countryCode, city, activeSince, value, mutualOnly,
                    maritalStatuses, minEducation, sects, smoking, children, nationality, withPhoto, memberIds,
                    excludeId, includeHidden, notLiked, notPickedSince);
        }

        /** Fluent construction: most queries set only a few of the filters. */
        public static final class Builder {

            private LocalDate bornOnOrBefore;
            private LocalDate bornAfter;
            private String countryCode;
            private String city;
            private LocalDateTime activeSince;
            private Integer radiusKm;
            private boolean mutualOnly;
            private Set<String> maritalStatuses = Set.of();
            private String minEducation;
            private Set<String> sects = Set.of();
            private Set<String> smoking = Set.of();
            private String children;
            private String nationality;
            private boolean withPhoto;
            private Set<UUID> memberIds;
            private UUID excludeId;
            private boolean includeHidden;
            private boolean notLiked;
            private LocalDate notPickedSince;

            public Builder born(LocalDate onOrBefore, LocalDate after) {
                this.bornOnOrBefore = onOrBefore;
                this.bornAfter = after;
                return this;
            }

            public Builder country(String value) {
                this.countryCode = value;
                return this;
            }

            public Builder city(String value) {
                this.city = value;
                return this;
            }

            public Builder activeSince(LocalDateTime value) {
                this.activeSince = value;
                return this;
            }

            public Builder radiusKm(Integer value) {
                this.radiusKm = value;
                return this;
            }

            public Builder mutualOnly(boolean value) {
                this.mutualOnly = value;
                return this;
            }

            public Builder maritalStatuses(Set<String> values) {
                this.maritalStatuses = values;
                return this;
            }

            public Builder minEducation(String value) {
                this.minEducation = value;
                return this;
            }

            public Builder sects(Set<String> values) {
                this.sects = values;
                return this;
            }

            public Builder smoking(Set<String> values) {
                this.smoking = values;
                return this;
            }

            public Builder children(String value) {
                this.children = value;
                return this;
            }

            public Builder nationality(String value) {
                this.nationality = value;
                return this;
            }

            public Builder withPhoto(boolean value) {
                this.withPhoto = value;
                return this;
            }

            public Builder memberIds(Set<UUID> values) {
                this.memberIds = values;
                return this;
            }

            public Builder excludeId(UUID value) {
                this.excludeId = value;
                return this;
            }

            public Builder includeHidden(boolean value) {
                this.includeHidden = value;
                return this;
            }

            public Builder notLiked(boolean value) {
                this.notLiked = value;
                return this;
            }

            public Builder notPickedSince(LocalDate value) {
                this.notPickedSince = value;
                return this;
            }

            public Filters build() {
                return new Filters(bornOnOrBefore, bornAfter, countryCode, city, activeSince, radiusKm, mutualOnly,
                        Set.copyOf(maritalStatuses), minEducation, Set.copyOf(sects), Set.copyOf(smoking), children,
                        nationality, withPhoto, memberIds == null ? null : Set.copyOf(memberIds), excludeId,
                        includeHidden, notLiked, notPickedSince);
            }
        }
    }

    /** Sort key of a row: the first key depends on the sort, then the secondary key, then the member id. */
    public record Keyset(BigDecimal primary, BigDecimal secondary, String id) {
    }
}
