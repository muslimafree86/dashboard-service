package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.util.List;

/** A row of {@code dashboard_places}: a governorate ({@code REGION}) or a city / district ({@code CITY}). */
public record Place(int id, String countryCode, String kind, Integer regionId, String nameAr, String nameEn,
        List<String> aliases, BigDecimal latitude, BigDecimal longitude) {

    public boolean isRegion() {
        return "REGION".equals(kind);
    }
}
