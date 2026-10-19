package com.muslimafree.dashboard.location;

import io.swagger.v3.oas.annotations.media.Schema;

/** A place of the gazetteer, without its coordinates (they are only used inside the search). */
public record PlaceResponse(
        int id,
        String countryCode,
        @Schema(description = "REGION (governorate) or CITY (city / district)") String kind,
        String nameAr,
        String nameEn,
        @Schema(description = "The governorate of a city, when known") Integer regionId) {

    public static PlaceResponse of(Place place) {
        return new PlaceResponse(place.id(), place.countryCode(), place.kind(), place.nameAr(), place.nameEn(),
                place.regionId());
    }
}
