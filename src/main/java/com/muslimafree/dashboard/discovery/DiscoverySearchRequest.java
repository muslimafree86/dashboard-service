package com.muslimafree.dashboard.discovery;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.muslimafree.dashboard.member.ChildrenStatus;
import com.muslimafree.dashboard.member.EducationLevel;
import com.muslimafree.dashboard.member.MaritalStatus;
import com.muslimafree.dashboard.member.Sect;
import com.muslimafree.dashboard.member.SmokingHabit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The Home search bar (Figma 77:626: ابحث عن / العمر / البلد / المدينة / علي مسافة كم) and the tabs, as query parameters.
 * plus the advanced filters on attributes every profile already has. Every parameter is optional; the filters given
 * are combined (AND), values of one multi-value filter are alternatives (OR). An attribute filter only matches members
 * who filled that attribute in. The sought gender is not a parameter: it is always derived from the signed-in member.
 */
public record DiscoverySearchRequest(
        @Schema(description = "العمر من", example = "20") @Min(18) @Max(99) Integer ageMin,
        @Schema(description = "العمر إلى", example = "45") @Min(18) @Max(99) Integer ageMax,
        @Schema(description = "البلد: ISO 3166-1 alpha-2", example = "EG") @Pattern(regexp = "[A-Za-z]{2}") String country,
        @Schema(description = "المدينة: matches the member's city or governorate", example = "القاهرة")
        @Size(max = 100) String city,
        @Schema(description = "علي مسافة كم: requires the viewer's shared location", example = "25")
        @Min(1) @Max(500) Integer distanceKm,
        @Schema(description = "Measure distances from this place (GET /places) instead of my own position: searching "
                + "another city before travelling or relocating") @Min(1) Integer near,
        @Schema(description = "متصل الان tab: only members active right now") Boolean online,
        @Schema(description = "الشركاء tab: only mutual matches") Boolean mutual,
        @Schema(description = "الحالة الاجتماعية, repeatable: maritalStatus=SINGLE&maritalStatus=DIVORCED")
        @Size(max = 4) List<MaritalStatus> maritalStatus,
        @Schema(description = "التعليم: at least this level") EducationLevel educationMin,
        @Schema(description = "الديانة, repeatable") @Size(max = 3) List<Sect> sect,
        @Schema(description = "التدخين, repeatable") @Size(max = 3) List<SmokingHabit> smoking,
        @Schema(description = "لديهم صغار") ChildrenStatus children,
        @Schema(description = "الجنسية: ISO 3166-1 alpha-2", example = "EG") @Pattern(regexp = "[A-Za-z]{2}") String nationality,
        @Schema(description = "Only members with an approved photo") Boolean withPhoto,
        @Schema(description = "Default RELEVANCE") DiscoverySort sort,
        @Schema(description = "Page size, default 20") @Min(1) @Max(50) Integer size,
        @Schema(description = "nextCursor of the previous page; omit for the first page") @Size(max = 512) String cursor) {

    public static final int DEFAULT_SIZE = 20;

    public int pageSize() {
        return size == null ? DEFAULT_SIZE : size;
    }

    public DiscoverySort sortOrDefault() {
        return sort == null ? DiscoverySort.RELEVANCE : sort;
    }

    public boolean onlineOnly() {
        return Boolean.TRUE.equals(online);
    }

    public boolean mutualOnly() {
        return Boolean.TRUE.equals(mutual);
    }

    public boolean withPhotoOnly() {
        return Boolean.TRUE.equals(withPhoto);
    }

    public Set<String> maritalStatusCodes() {
        return codes(maritalStatus);
    }

    public Set<String> sectCodes() {
        return codes(sect);
    }

    public Set<String> smokingCodes() {
        return codes(smoking);
    }

    private static Set<String> codes(List<? extends Enum<?>> values) {
        return values == null ? Set.of() : values.stream().map(Enum::name).collect(Collectors.toSet());
    }
}
