package com.muslimafree.dashboard.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/**
 * Nearby search under pressure: nearest order beyond the activity-ordered candidate pool, nothing more precise than the
 * card leaking through cursors or the radius, parallel location updates, and the incremental index passes.
 */
@TestPropertySource(properties = {"app.discovery.candidate-pool=20", "app.location.max-changes-per-day=3"})
class NearestIT extends AbstractIntegrationTest {

    private static final String LOCATION = "/api/v1/dashboard/me/location";

    private String feed(Member as, String query) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/members?" + query).with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> ids(Member as, String query) throws Exception {
        return JsonPath.read(feed(as, query), "$.items[*].id");
    }

    private static String id(Member m) {
        return m.id().toString();
    }

    private Member man(String region, String city) {
        return members.create(s -> { s.gender = "MALE"; s.region = region; s.city = city; });
    }

    @Test
    void nearestFindsCloseMembersEvenWhenManyFarOnesAreMoreActive() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "أسوان"; s.city = "أسوان"; });
        members.locate(viewer.id(), 24.09, 32.90);
        for (int i = 0; i < 24; i++) {
            Member cairo = man("القاهرة", "المعادي");
            members.locate(cairo.id(), 30.00 + i * 0.01, 31.25);
        }
        // far less active than the 24 in Cairo, so outside a 20-member pool ordered by activity
        Member neighbour = members.create(s -> { s.gender = "MALE"; s.region = "أسوان"; s.city = "أسوان";
            s.lastActiveAt = NOW.minus(Duration.ofDays(20)); });
        members.locate(neighbour.id(), 24.10, 32.91);

        assertThat(ids(viewer, "sort=NEAREST&size=3")).first().isEqualTo(id(neighbour));

        // every page continues from the previous one's distance: all 25, once each, never closer than before
        List<String> walked = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();
        String cursor = null;
        do {
            String body = feed(viewer, "sort=NEAREST&size=4" + (cursor == null ? "" : "&cursor=" + cursor));
            walked.addAll(JsonPath.read(body, "$.items[*].id"));
            distances.addAll(JsonPath.read(body, "$.items[*].distanceKm"));
            cursor = JsonPath.read(body, "$.hasMore") ? JsonPath.read(body, "$.nextCursor") : null;
        } while (cursor != null);
        assertThat(walked).hasSize(25).doesNotHaveDuplicates();
        assertThat(distances).isSorted();
    }

    @Test
    void cursorsCarryNoMoreThanTheDistanceOnTheCard() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        members.locate(viewer.id(), 29.96, 31.25);
        members.locate(man("القاهرة", "المعادي").id(), 30.04, 31.24); // 8.94 km
        members.locate(man("القاهرة", "المعادي").id(), 30.06, 31.50); // ~27 km

        String body = feed(viewer, "sort=NEAREST&size=1");
        Integer shown = JsonPath.read(body, "$.items[0].distanceKm");
        String raw = new String(Base64.getUrlDecoder().decode((String) JsonPath.read(body, "$.nextCursor")),
                StandardCharsets.UTF_8);

        assertThat(shown).isEqualTo(9);
        assertThat(new BigDecimal(raw.split("\\|")[3])).isEqualByComparingTo("9");
    }

    @Test
    void theRadiusAppliesToTheShownDistance() throws Exception {
        // no device location: distances are measured from the centre of Maadi and shown in 5 km steps
        Member viewer = members.create(s -> s.gender = "FEMALE");
        Member sameDistrict = man("أسوان", "أسوان");
        members.locate(sameDistrict.id(), 29.97, 31.26);

        assertThat(ids(viewer, "distanceKm=2")).containsExactly(id(sameDistrict));
        String body = feed(viewer, "distanceKm=2");
        assertThat((Integer) JsonPath.read(body, "$.items[0].distanceKm")).isEqualTo(5);
        assertThat((Boolean) JsonPath.read(body, "$.items[0].distanceApproximate")).isTrue();
    }

    @Test
    void parallelLocationUpdatesCannotExceedTheDailyCap() throws Exception {
        Member member = members.create(s -> s.gender = "FEMALE");
        List<Callable<Integer>> moves = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String json = "{\"latitude\":30." + (10 + i) + ",\"longitude\":31.2}";
            moves.add(() -> mvc.perform(put(LOCATION).with(as(member)).contentType(MediaType.APPLICATION_JSON)
                    .content(json)).andReturn().getResponse().getStatus());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : pool.invokeAll(moves)) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT changes_count FROM dashboard_member_locations WHERE user_id = ?",
                Integer.class, id(member))).isEqualTo(3);
    }

    @Test
    void placeChangesQueuedByTheProfileServiceAreIndexedByTheIncrementalPass() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        Member mover = man("أسوان", "أسوان");
        locationIndexer.reconcileAll();
        assertThat(ids(viewer, "distanceKm=50")).isEmpty();

        // what user-profile-managment does on a profile edit (PlaceChangeRecorder)
        jdbc.update("UPDATE user_profiles SET region = 'القاهرة', city = 'حلوان' WHERE user_id = ?", id(mover));
        jdbc.update("INSERT INTO dashboard_location_changes (user_id, changed_at) VALUES (?, ?)", id(mover),
                Timestamp.valueOf(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(locationIndexer.applyQueuedChanges()).isEqualTo(1);
        assertThat(ids(viewer, "distanceKm=50")).containsExactly(id(mover));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_location_changes", Integer.class)).isZero();
        assertThat(locationIndexer.applyQueuedChanges()).isZero();
    }

    @Test
    void staleDeviceLocationsAreExpiredByTheIncrementalPass() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        Member him = man("أسوان", "أسوان");
        members.locate(him.id(), 29.96, 31.26);
        assertThat(ids(viewer, "distanceKm=5")).containsExactly(id(him));

        clock.advance(Duration.ofDays(31));
        assertThat(locationIndexer.expireStaleDevices()).isEqualTo(1);
        assertThat(ids(viewer, "distanceKm=5")).isEmpty();
        assertThat(locationIndexer.expireStaleDevices()).isZero();
    }

    @Test
    void myLocationSaysWhenItStopsCounting() throws Exception {
        Member member = members.create(s -> s.gender = "FEMALE");
        mvc.perform(put(LOCATION).with(as(member)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":30.0,\"longitude\":31.2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-05T12:00:00Z"));

        clock.advance(Duration.ofDays(31));
        mvc.perform(get(LOCATION).with(as(member))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void placesOutsideEgyptsBigCitiesAreKnown() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        mvc.perform(get("/api/v1/dashboard/places?q=الطايف&country=SA").with(as(viewer)))
                .andExpect(jsonPath("$[0].nameEn").value("Taif"));
        mvc.perform(get("/api/v1/dashboard/places?q=al ahsa").with(as(viewer)))
                .andExpect(jsonPath("$[0].nameEn").value("Hofuf"));

        Member taif = members.create(s -> { s.gender = "MALE"; s.countryCode = "SA"; s.region = "مكة"; s.city = "الطائف"; });
        Member viewerInMecca = members.create(s -> { s.gender = "FEMALE"; s.countryCode = "SA"; s.region = "مكة";
            s.city = "مكة المكرمة"; });
        locationIndexer.reconcileAll();
        assertThat(ids(viewerInMecca, "distanceKm=100")).contains(id(taif));
    }
}
