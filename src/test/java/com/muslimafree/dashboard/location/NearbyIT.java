package com.muslimafree.dashboard.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/**
 * Nearby search for every member: device positions where shared and fresh, otherwise the centre of the profile's
 * city / governorate; searching around another place; distance in the ranking; anti-trilateration limits; places.
 */
@TestPropertySource(properties = {"app.location.max-changes-per-day=3", "app.rate-limit.per-minute=8"})
class NearbyIT extends AbstractIntegrationTest {

    private List<String> ids(Member as, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard/members?" + query).with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].id");
    }

    private static String id(Member m) {
        return m.id().toString();
    }

    private Member man(String region, String city) {
        return members.create(s -> { s.gender = "MALE"; s.region = region; s.city = city; });
    }

    @Test
    void membersWithoutADeviceLocationAreFoundByTheirCityOrGovernorate() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        Member maadi = man("القاهرة", "المعادي");                 // same district: ~0 km
        Member nasrCity = man("محافظة القاهرة", "مدينة نصر");      // ~12 km, spelled with "محافظة"
        Member giza = man("الجيزة", "مكان لا يعرفه الدليل");       // unknown district -> Giza governorate, ~11 km
        Member alex = man("الاسكندريه", "سموحة");                  // ~180 km
        Member nowhere = man("?", "?");                            // no position at all
        locationIndexer.reconcileAll();

        assertThat(ids(viewer, "distanceKm=15")).containsExactlyInAnyOrder(id(maadi), id(nasrCity), id(giza));
        assertThat(ids(viewer, "distanceKm=200")).contains(id(alex)).doesNotContain(id(nowhere));
        // both are shown "5 km": the order never says more than the card, so equal shown distances go by relevance
        List<String> nearest = ids(viewer, "sort=NEAREST&size=3");
        assertThat(nearest.subList(0, 2)).containsExactlyInAnyOrder(id(maadi), id(giza));
        assertThat(nearest.get(2)).isEqualTo(id(nasrCity));
        // approximate distances are flagged and rounded to 5 km (never below 5)
        mvc.perform(get("/api/v1/dashboard/members?distanceKm=2").with(as(viewer)))
                .andExpect(jsonPath("$.items[0].id").value(id(maadi)))
                .andExpect(jsonPath("$.items[0].distanceKm").value(5))
                .andExpect(jsonPath("$.items[0].distanceApproximate").value(true));
        // without a filter, members without any position simply have no distance
        String feed = mvc.perform(get("/api/v1/dashboard/members").with(as(viewer)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(feed, "$.items[?(@.id == '" + id(nowhere) + "')].distanceKm"))
                .isEmpty();
    }

    @Test
    void devicePositionsArePreciseAndWinOverTheCity() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        members.locate(viewer.id(), 29.96, 31.25);
        // his profile says Alexandria, but his phone is in Maadi today
        Member visiting = man("الإسكندرية", "سموحة");
        members.locate(visiting.id(), 29.97, 31.25);

        mvc.perform(get("/api/v1/dashboard/members?distanceKm=5").with(as(viewer)))
                .andExpect(jsonPath("$.items[0].id").value(id(visiting)))
                .andExpect(jsonPath("$.items[0].distanceKm").value(1))
                .andExpect(jsonPath("$.items[0].distanceApproximate").value(false));
    }

    @Test
    void staleDeviceLocationsFallBackToTheCity() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        Member traveller = man("الإسكندرية", "سموحة");
        members.locate(traveller.id(), 29.97, 31.25); // shared once, in Cairo
        assertThat(ids(viewer, "distanceKm=10")).containsExactly(id(traveller));

        clock.advance(Duration.ofDays(31));
        locationIndexer.reconcileAll();

        assertThat(ids(viewer, "distanceKm=10")).isEmpty();
        assertThat(ids(viewer, "distanceKm=250")).containsExactly(id(traveller));
    }

    @Test
    void profileEditsReachTheIndexOnTheNextPass() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        Member mover = man("أسوان", "أسوان");
        locationIndexer.reconcileAll();
        assertThat(ids(viewer, "distanceKm=50")).isEmpty();

        jdbc.update("UPDATE user_profiles SET region = 'القاهرة', city = 'حلوان' WHERE user_id = ?", id(mover));
        locationIndexer.reconcileAll();

        assertThat(ids(viewer, "distanceKm=50")).containsExactly(id(mover));
        assertThat(locationIndexer.reconcileAll()).as("nothing left to change").isZero();
    }

    @Test
    void searchAroundAnotherPlace() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        Member cairo = man("القاهرة", "المعادي");
        Member alex = man("الإسكندرية", "المنتزه");
        locationIndexer.reconcileAll();
        String places = mvc.perform(get("/api/v1/dashboard/places?q=اسكندريه").with(as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer alexandria = JsonPath.read(places, "$[0].id");

        assertThat(ids(viewer, "near=" + alexandria + "&distanceKm=30")).containsExactly(id(alex));
        assertThat(ids(viewer, "near=" + alexandria + "&sort=NEAREST")).containsExactly(id(alex), id(cairo));
        mvc.perform(get("/api/v1/dashboard/members?near=99999").with(as(viewer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_PLACE"));
    }

    @Test
    void closerMembersRankHigherWhenOtherwiseEqual() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.countryCode = "EG"; s.region = "القاهرة";
            s.city = "المعادي"; });
        // same governorate text for both, so only the distance can separate them
        Member far = man("القاهرة", "?");
        Member near = man("القاهرة", "?");
        members.locate(viewer.id(), 29.96, 31.25);
        members.locate(far.id(), 30.30, 31.70);   // ~58 km
        members.locate(near.id(), 29.98, 31.26);  // ~2 km

        List<String> order = ids(viewer, "");
        assertThat(order.indexOf(id(near))).isLessThan(order.indexOf(id(far)));
    }

    @Test
    void movingOnesPositionIsLimitedPerDay() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        String path = "/api/v1/dashboard/me/location";
        for (int i = 0; i < 3; i++) {
            mvc.perform(put(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"latitude\":30.0" + i + ",\"longitude\":31.2}")).andExpect(status().isOk());
        }
        // refreshing the same position is free
        mvc.perform(put(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":30.02,\"longitude\":31.2}")).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1)); // stay under this test's browsing budget
        mvc.perform(put(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":30.5,\"longitude\":31.2}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOCATION_CHANGE_LIMIT_REACHED"));

        clock.advance(Duration.ofDays(1));
        mvc.perform(put(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":30.5,\"longitude\":31.2}")).andExpect(status().isOk());
    }

    @Test
    void sharingAndRemovingADeviceLocationUpdatesSearchAtOnce() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.region = "القاهرة"; s.city = "المعادي"; });
        Member him = man("أسوان", "أسوان");
        locationIndexer.reconcileAll();
        assertThat(ids(viewer, "distanceKm=5")).isEmpty();

        mvc.perform(put("/api/v1/dashboard/me/location").with(as(him)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":29.96,\"longitude\":31.26}")).andExpect(status().isOk());
        assertThat(ids(viewer, "distanceKm=5")).containsExactly(id(him));

        clock.advance(Duration.ofMinutes(1));
        mvc.perform(delete("/api/v1/dashboard/me/location").with(as(him))).andExpect(status().isNoContent());
        assertThat(ids(viewer, "distanceKm=5")).isEmpty();
    }

    @Test
    void placeAutocomplete() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        mvc.perform(get("/api/v1/dashboard/places?q=maadi").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nameAr").value("المعادي"))
                .andExpect(jsonPath("$[0].kind").value("CITY"))
                .andExpect(jsonPath("$[0].latitude").doesNotExist());
        mvc.perform(get("/api/v1/dashboard/places?q=الجيزه").with(as(viewer)))
                .andExpect(jsonPath("$[0].nameEn").value("Giza"))
                .andExpect(jsonPath("$[0].kind").value("REGION"));
        mvc.perform(get("/api/v1/dashboard/places?q=دب&country=AE").with(as(viewer)))
                .andExpect(jsonPath("$[0].nameEn").value("Dubai"));
        mvc.perform(get("/api/v1/dashboard/places?q=").with(as(viewer))).andExpect(status().isBadRequest());
        // autocomplete fires on every keystroke: not counted against this test's 8-per-minute browsing budget
        for (int i = 0; i < 12; i++) {
            mvc.perform(get("/api/v1/dashboard/places?q=ق").with(as(viewer))).andExpect(status().isOk());
        }
    }
}
