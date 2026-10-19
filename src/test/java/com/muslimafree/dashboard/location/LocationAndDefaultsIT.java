package com.muslimafree.dashboard.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

class LocationAndDefaultsIT extends AbstractIntegrationTest {

    private static final String LOCATION = "/api/v1/dashboard/me/location";

    private Member viewer;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> { s.gender = "FEMALE"; s.prefAgeMin = 25; s.prefAgeMax = 45;
            s.prefCountryCode = "EG"; s.prefRegion = "القاهرة"; });
    }

    @Test
    void locationIsSnappedBeforeItIsStored() throws Exception {
        mvc.perform(put(LOCATION).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":29.961234,\"longitude\":31.256789}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(29.96))
                .andExpect(jsonPath("$.longitude").value(31.26));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT latitude, longitude FROM dashboard_member_locations WHERE user_id = ?", viewer.id().toString());
        assertThat((BigDecimal) row.get("latitude")).isEqualByComparingTo("29.96");
        assertThat((BigDecimal) row.get("longitude")).isEqualByComparingTo("31.26");
    }

    @Test
    void readUpdateAndStopSharing() throws Exception {
        mvc.perform(get(LOCATION).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(put(LOCATION).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":30.0,\"longitude\":31.2}")).andExpect(status().isOk());
        mvc.perform(put(LOCATION).with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":30.5,\"longitude\":31.5}")).andExpect(status().isOk());
        mvc.perform(get(LOCATION).with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(30.5));

        mvc.perform(delete(LOCATION).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(get(LOCATION).with(as(viewer))).andExpect(status().isNoContent());
        // distances now come from her district (المعادي), approximately
        mvc.perform(get("/api/v1/dashboard/filters").with(as(viewer)))
                .andExpect(jsonPath("$.locationSource").value("CITY"));
    }

    @Test
    void invalidCoordinatesAreRejected() throws Exception {
        for (String body : new String[] {"{\"latitude\":91,\"longitude\":31}", "{\"latitude\":30,\"longitude\":-181}",
                "{\"latitude\":30}", "{}", "not json"}) {
            mvc.perform(put(LOCATION).with(as(viewer)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void locationRowDisappearsWithTheProfile() throws Exception {
        members.locate(viewer.id(), 30, 31);
        // what user-profile-managment's account purge does
        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", viewer.id().toString());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_member_locations", Integer.class)).isZero();
    }

    @Test
    void searchBarIsPrefilledFromPartnerPreferences() throws Exception {
        mvc.perform(get("/api/v1/dashboard/filters").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lookingForGender").value("MALE"))
                .andExpect(jsonPath("$.ageMin").value(25))
                .andExpect(jsonPath("$.ageMax").value(45))
                .andExpect(jsonPath("$.country").value("EG"))
                .andExpect(jsonPath("$.city").value("القاهرة"))
                .andExpect(jsonPath("$.locationSource").value("CITY"))
                .andExpect(jsonPath("$.locationPlace.nameEn").value("Maadi"))
                .andExpect(jsonPath("$.limits.minAge").value(18))
                .andExpect(jsonPath("$.limits.maxDistanceKm").value(500));
    }

    @Test
    void withoutPreferencesTheOwnCountryIsSuggested() throws Exception {
        Member noPrefs = members.create(s -> { s.gender = "MALE"; s.countryCode = "SA"; });

        mvc.perform(get("/api/v1/dashboard/filters").with(as(noPrefs))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lookingForGender").value("FEMALE"))
                .andExpect(jsonPath("$.ageMin").doesNotExist())
                .andExpect(jsonPath("$.country").value("SA"));
    }
}
