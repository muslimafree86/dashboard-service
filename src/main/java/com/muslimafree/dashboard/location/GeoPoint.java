package com.muslimafree.dashboard.location;

import java.math.BigDecimal;

/** A search position: coordinates, how precise they are, and the place they stand for when approximate. */
public record GeoPoint(BigDecimal latitude, BigDecimal longitude, LocationSource source, Integer placeId) {
}
