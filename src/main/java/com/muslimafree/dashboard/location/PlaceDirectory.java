package com.muslimafree.dashboard.location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The gazetteer ({@code dashboard_places}, a few hundred rows) held in memory: resolves the free-text city and
 * governorate of a profile to coordinates, and answers place autocomplete.
 */
@Component
public class PlaceDirectory {

    private final Map<Integer, Place> byId = new HashMap<>();
    /** country -> normalised name or alias -> places (a name can be both a governorate and its capital city) */
    private final Map<String, Map<String, List<Place>>> byName = new HashMap<>();
    private final List<Indexed> searchable;

    private record Indexed(Place place, List<String> names) {
    }

    public PlaceDirectory(JdbcTemplate jdbc) {
        List<Place> places = jdbc.query("""
                SELECT id, country_code, kind, region_id, name_ar, name_en, aliases, latitude, longitude
                FROM dashboard_places
                """, (rs, i) -> new Place(rs.getInt("id"), rs.getString("country_code"), rs.getString("kind"),
                (Integer) rs.getObject("region_id", Integer.class), rs.getString("name_ar"), rs.getString("name_en"),
                rs.getString("aliases") == null ? List.of() : Arrays.asList(rs.getString("aliases").split("\\|")),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")));
        searchable = places.stream().map(p -> new Indexed(p, Stream.concat(Stream.of(p.nameAr(), p.nameEn()),
                p.aliases().stream()).map(PlaceNames::normalize).filter(n -> !n.isEmpty()).distinct().toList()))
                .toList();
        for (Indexed indexed : searchable) {
            byId.put(indexed.place().id(), indexed.place());
            for (String name : indexed.names()) {
                byName.computeIfAbsent(indexed.place().countryCode(), c -> new HashMap<>())
                        .computeIfAbsent(name, n -> new ArrayList<>()).add(indexed.place());
            }
        }
    }

    public Optional<Place> find(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Place> region(Place place) {
        return place.regionId() == null ? Optional.empty() : find(place.regionId());
    }

    /**
     * The approximate position of a profile: its city (or district) when known, else its governorate. Members type
     * either into either field, so both are tried against both kinds, city first.
     */
    public Optional<GeoPoint> resolve(String countryCode, String region, String city) {
        if (countryCode == null) {
            return Optional.empty();
        }
        Map<String, List<Place>> names = byName.getOrDefault(countryCode.toUpperCase(Locale.ROOT), Map.of());
        List<Place> cityMatches = names.getOrDefault(PlaceNames.normalize(city), List.of());
        List<Place> regionMatches = names.getOrDefault(PlaceNames.normalize(region), List.of());
        return Stream.of(
                        cityMatches.stream().filter(p -> !p.isRegion()),
                        regionMatches.stream().filter(p -> !p.isRegion()),
                        regionMatches.stream().filter(Place::isRegion),
                        cityMatches.stream().filter(Place::isRegion))
                .flatMap(s -> s)
                .findFirst()
                .map(p -> new GeoPoint(p.latitude(), p.longitude(),
                        p.isRegion() ? LocationSource.REGION : LocationSource.CITY, p.id()));
    }

    /** Autocomplete: names (or aliases) starting with the typed text; exact names first, governorates first. */
    public List<Place> search(String text, String countryCode, int limit) {
        String typed = PlaceNames.normalize(text);
        if (typed.isEmpty()) {
            return List.of();
        }
        return searchable.stream()
                .filter(i -> countryCode == null || i.place().countryCode().equalsIgnoreCase(countryCode))
                .filter(i -> i.names().stream().anyMatch(n -> n.startsWith(typed)))
                .sorted(Comparator.comparing((Indexed i) -> !i.names().contains(typed))
                        .thenComparing(i -> !i.place().isRegion())
                        .thenComparing(i -> i.place().nameEn()))
                .limit(limit)
                .map(Indexed::place)
                .toList();
    }
}
