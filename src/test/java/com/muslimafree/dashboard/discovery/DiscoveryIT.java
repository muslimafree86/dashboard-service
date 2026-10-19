package com.muslimafree.dashboard.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;
import com.muslimafree.dashboard.support.TestTokens;

/**
 * The Home feed end to end: real SQL on MariaDB/MySQL, real token validation, real JSON.
 *
 * <p>Cast: the viewer is Fatima (30, Cairo, Sunni, non-smoker, bachelor) looking for a man of 28-40 in Egypt, Sunni,
 * non-smoker. {@code perfect} matches her preferences and she matches his; {@code oneWay} matches hers but wants a
 * younger wife; {@code poor} contradicts most of what she looks for.
 */
class DiscoveryIT extends AbstractIntegrationTest {

    private static final String FEED = "/api/v1/dashboard/members";

    private Member viewer;
    private Member perfect;
    private Member oneWay;
    private Member poor;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> {
            s.firstName = "فاطمة";
            s.gender = "FEMALE";
            s.age = 30;
            s.education = "BACHELOR";
            s.smoking = "NO";
            s.sect = "SUNNI";
            s.prefAgeMin = 28;
            s.prefAgeMax = 40;
            s.prefCountryCode = "EG";
            s.prefSect = "SUNNI";
            s.prefSmoking = "NO";
        });
        perfect = members.create(s -> {
            s.firstName = "عبدالله";
            s.lastName = "سليم";
            s.gender = "MALE";
            s.age = 32;
            s.smoking = "NO";
            s.sect = "SUNNI";
            s.prefAgeMin = 25;
            s.prefAgeMax = 35;
            s.prefSect = "SUNNI";
            s.prefMinEducation = "BACHELOR";
        });
        oneWay = members.create(s -> {
            s.gender = "MALE";
            s.age = 33;
            s.smoking = "NO";
            s.sect = "SUNNI";
            s.prefAgeMin = 20;
            s.prefAgeMax = 25;
        });
        poor = members.create(s -> {
            s.gender = "MALE";
            s.age = 50;
            s.smoking = "YES";
            s.sect = "SHIA";
            s.region = "الاسكندرية";
            s.city = "سموحة";
        });
    }

    private ResultActions feed(Member as, String query) throws Exception {
        MockHttpServletRequestBuilder request = get(FEED + (query.isEmpty() ? "" : "?" + query)).with(as(as));
        return mvc.perform(request);
    }

    private List<String> ids(Member as, String query) throws Exception {
        String body = feed(as, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].id");
    }

    private static String id(Member member) {
        return member.id().toString();
    }

    // ------------------------------------------------------------------------------------------------ eligibility

    @Nested
    class Eligibility {

        @Test
        void showsOnlyMembersTheViewerIsAllowedToSee() throws Exception {
            members.create(s -> { s.gender = "MALE"; s.visibility = "HIDDEN"; });
            members.create(s -> { s.gender = "MALE"; s.emailVerified = false; });
            members.create(s -> { s.gender = "MALE"; s.onboardingStep = "APPEARANCE"; });
            members.create(s -> { s.gender = "MALE"; s.status = "SUSPENDED"; });
            members.create(s -> { s.gender = "MALE"; s.status = "DEACTIVATED"; });
            members.create(s -> { s.gender = "MALE"; s.status = "PENDING_DELETION"; });
            members.create(s -> s.gender = "FEMALE");
            Member blockedByViewer = members.create(s -> s.gender = "MALE");
            Member blockedTheViewer = members.create(s -> s.gender = "MALE");
            members.block(viewer.id(), blockedByViewer.id());
            members.block(blockedTheViewer.id(), viewer.id());

            assertThat(ids(viewer, "")).containsExactlyInAnyOrder(id(perfect), id(oneWay), id(poor));
        }

        @Test
        void menSeeWomenAndNeverThemselves() throws Exception {
            assertThat(ids(perfect, "")).containsExactly(id(viewer));
        }

        @Test
        void aViewerWhoHasNotFinishedOnboardingCannotBrowse() throws Exception {
            Member newcomer = members.create(s -> s.onboardingStep = "DETAILS");

            feed(newcomer, "").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INCOMPLETE"));
        }

        @Test
        void emptyFeedIsAnEmptyPage() throws Exception {
            members.block(viewer.id(), perfect.id());
            members.block(viewer.id(), oneWay.id());
            members.block(viewer.id(), poor.id());

            feed(viewer, "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }
    }

    // ------------------------------------------------------------------------------------------------ ranking

    @Nested
    class Ranking {

        @Test
        void bestTwoWayMatchFirst() throws Exception {
            assertThat(ids(viewer, "")).containsExactly(id(perfect), id(oneWay), id(poor));
        }

        @Test
        void reciprocityCountsTheViewerMustAlsoBeWhatTheyLookFor() throws Exception {
            // identical on Fatima's side; only `wantsSomeoneElse` cannot accept her (wants a non-Sunni wife)
            Member wantsHer = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI"; s.smoking = "NO";
                s.prefSect = "SUNNI"; });
            Member wantsSomeoneElse = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI";
                s.smoking = "NO"; s.prefSect = "SHIA"; });

            List<String> order = ids(viewer, "");
            assertThat(order.indexOf(id(wantsHer))).isLessThan(order.indexOf(id(wantsSomeoneElse)));
        }

        @Test
        void recentActivityBreaksOtherwiseEqualMatches() throws Exception {
            Member activeNow = members.create(s -> { s.gender = "MALE"; s.age = 35; s.lastActiveAt = NOW; });
            Member activeLastMonth = members.create(s -> { s.gender = "MALE"; s.age = 35;
                s.lastActiveAt = NOW.minus(Duration.ofDays(20)); });

            List<String> order = ids(viewer, "");
            assertThat(order.indexOf(id(activeNow))).isLessThan(order.indexOf(id(activeLastMonth)));
        }

        @Test
        void unknownAttributesRankBetweenAMatchAndAContradiction() throws Exception {
            Member nonSmoker = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI"; s.smoking = "NO"; });
            Member didNotSay = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI"; s.smoking = null; });
            Member smoker = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI"; s.smoking = "YES"; });

            List<String> order = ids(viewer, "");
            assertThat(order.indexOf(id(nonSmoker))).isLessThan(order.indexOf(id(didNotSay)));
            assertThat(order.indexOf(id(didNotSay))).isLessThan(order.indexOf(id(smoker)));
        }

        @Test
        void rankingIsDeterministic() throws Exception {
            for (int i = 0; i < 5; i++) {
                members.create(s -> { s.gender = "MALE"; s.age = 35; s.lastActiveAt = NOW.minusSeconds(3600); });
            }
            assertThat(ids(viewer, "")).isEqualTo(ids(viewer, ""));
        }

        @Test
        void recentlyActiveOrder() throws Exception {
            jdbc.update("UPDATE users SET last_active_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf("2026-10-06 11:59:30"), id(poor));
            jdbc.update("UPDATE users SET last_active_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf("2026-10-01 00:00:00"), id(perfect));

            assertThat(ids(viewer, "sort=RECENTLY_ACTIVE")).containsExactly(id(poor), id(oneWay), id(perfect));
        }
    }

    // ------------------------------------------------------------------------------------------------ filters

    @Nested
    class Filters {

        @Test
        void ageRange() throws Exception {
            assertThat(ids(viewer, "ageMin=31&ageMax=33")).containsExactlyInAnyOrder(id(perfect), id(oneWay));
            assertThat(ids(viewer, "ageMin=33")).containsExactlyInAnyOrder(id(oneWay), id(poor));
            assertThat(ids(viewer, "ageMax=32")).containsExactly(id(perfect));
        }

        @Test
        void ageBoundariesAreInclusive() throws Exception {
            assertThat(ids(viewer, "ageMin=32&ageMax=32")).containsExactly(id(perfect));
        }

        @Test
        void country() throws Exception {
            Member saudi = members.create(s -> { s.gender = "MALE"; s.countryCode = "SA"; s.region = "الرياض"; });

            assertThat(ids(viewer, "country=sa")).containsExactly(id(saudi));
            assertThat(ids(viewer, "country=EG")).containsExactlyInAnyOrder(id(perfect), id(oneWay), id(poor));
        }

        @Test
        void cityMatchesTheCityOrTheGovernorate() throws Exception {
            assertThat(ids(viewer, "city=الاسكندرية")).containsExactly(id(poor));
            assertThat(ids(viewer, "city=سموحة")).containsExactly(id(poor));
            assertThat(ids(viewer, "city=القاهرة")).containsExactlyInAnyOrder(id(perfect), id(oneWay));
        }

        @Test
        void filtersCombineAsAnIntersection() throws Exception {
            assertThat(ids(viewer, "country=EG&city=القاهرة&ageMin=33&ageMax=60")).containsExactly(id(oneWay));
        }

        @Test
        void onlineNowTab() throws Exception {
            jdbc.update("UPDATE users SET last_active_at = ? WHERE id IN (?, ?)",
                    java.sql.Timestamp.valueOf("2026-10-06 10:00:00"), id(perfect), id(poor));

            assertThat(ids(viewer, "online=true")).containsExactly(id(oneWay));
        }

        @Test
        void partnersTabListsOnlyMutualMatches() throws Exception {
            // perfect: both sides satisfied. oneWay: wants 20-25 (Fatima is 30). poor: 50, smoker, Shia.
            assertThat(ids(viewer, "mutual=true")).containsExactly(id(perfect));
            // and the other way round: from perfect's side, Fatima is a mutual match too
            assertThat(ids(perfect, "mutual=true")).containsExactly(id(viewer));
        }

        @Test
        void mutualToleratesAttributesNotFilledIn() throws Exception {
            Member silent = members.create(s -> { s.gender = "MALE"; s.age = 35; s.smoking = null; s.sect = null; });

            assertThat(ids(viewer, "mutual=true")).contains(id(silent));
        }

        @Test
        void filterValuesAreNeverPartOfTheSql() throws Exception {
            assertThat(ids(viewer, "city=x' OR '1'='1")).isEmpty();
        }

        @Test
        void invalidFiltersAreRejected() throws Exception {
            feed(viewer, "ageMin=40&ageMax=30").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AGE_RANGE"));
            feed(viewer, "ageMin=17").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            feed(viewer, "country=EGY").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            feed(viewer, "distanceKm=0").andExpect(status().isBadRequest());
            feed(viewer, "distanceKm=501").andExpect(status().isBadRequest());
            feed(viewer, "size=51").andExpect(status().isBadRequest());
            feed(viewer, "sort=PRETTIEST").andExpect(status().isBadRequest());
        }

        @Test
        void errorsAreLocalized() throws Exception {
            mvc.perform(get(FEED + "?ageMin=40&ageMax=30").with(as(viewer)).header("Accept-Language", "ar"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("يجب ألا يكون العمر \"من\" أكبر من العمر \"إلى\""));
        }
    }

    // ------------------------------------------------------------------------------------------------ distance

    @Nested
    class Nearby {

        @BeforeEach
        void places() {
            members.locate(viewer.id(), 29.96, 31.25);   // Maadi
            members.locate(perfect.id(), 30.04, 31.24);  // Downtown Cairo, ~9 km
            members.locate(oneWay.id(), 30.06, 31.50);   // ~27 km
            // poor never shared a location
        }

        @Test
        void distanceFilterKeepsMembersWithinTheRadius() throws Exception {
            assertThat(ids(viewer, "distanceKm=10")).containsExactly(id(perfect));
            assertThat(ids(viewer, "distanceKm=30")).containsExactlyInAnyOrder(id(perfect), id(oneWay));
        }

        @Test
        void membersWithoutALocationAreNotInADistanceSearchButStayInTheFeed() throws Exception {
            assertThat(ids(viewer, "distanceKm=500")).doesNotContain(id(poor));
            assertThat(ids(viewer, "")).contains(id(poor));
        }

        @Test
        void nearestFirst() throws Exception {
            assertThat(ids(viewer, "sort=NEAREST")).containsExactly(id(perfect), id(oneWay));
        }

        @Test
        void cardsShowARoundedDistanceAndNeverCoordinates() throws Exception {
            String body = feed(viewer, "distanceKm=30").andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].distanceKm").value(9))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("latitude", "longitude", "30.04", "31.24");
        }

        @Test
        void distanceIsNeverBelowOneKilometre() throws Exception {
            members.locate(perfect.id(), 29.96, 31.25); // same grid cell as the viewer

            feed(viewer, "distanceKm=5").andExpect(jsonPath("$.items[0].distanceKm").value(1));
        }

        @Test
        void distanceSearchNeedsTheViewersLocation() throws Exception {
            feed(perfect, "distanceKm=10").andExpect(status().isOk());
            // no device location and a city / governorate the gazetteer does not know: no position at all
            Member noLocation = members.create(s -> { s.gender = "MALE"; s.region = "مكان غير معروف"; s.city = "?"; });

            feed(noLocation, "distanceKm=10").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
            feed(noLocation, "sort=NEAREST").andExpect(status().isConflict());
        }

        @Test
        void distanceCombinesWithOtherFilters() throws Exception {
            assertThat(ids(viewer, "distanceKm=30&ageMin=33")).containsExactly(id(oneWay));
        }
    }

    // ------------------------------------------------------------------------------------------------ cards

    @Nested
    class Cards {

        /** user-profile-managment's rule: until staff approve a member, other members see "new user" instead. */
        @Test
        void aMemberNotApprovedYetIsShownAsNewUser() throws Exception {
            jdbc.update("UPDATE users SET review_status = 'PENDING_REVIEW' WHERE id = ?", perfect.id().toString());

            mvc.perform(get(FEED).with(as(viewer)).header("Accept-Language", "ar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(id(perfect)))
                    .andExpect(jsonPath("$.items[0].firstName").value("مستخدم جديد"))
                    .andExpect(jsonPath("$.items[0].lastName").value(""))
                    // only the name is hidden
                    .andExpect(jsonPath("$.items[0].age").value(32))
                    .andExpect(jsonPath("$.items[0].location.city").value("المعادي"));
            mvc.perform(get(FEED).with(as(viewer)).header("Accept-Language", "en"))
                    .andExpect(jsonPath("$.items[0].firstName").value("New user"));

            jdbc.update("UPDATE users SET review_status = 'REJECTED', review_reason = 'Fake name' WHERE id = ?",
                    perfect.id().toString());
            feed(viewer, "").andExpect(jsonPath("$.items[0].lastName").value(""));

            // staff always see the real name
            String staff = TestTokens.withRoles(viewer.id(), viewer.sessionId(), List.of("MEMBER", "MODERATOR"));
            mvc.perform(get(FEED).with(bearer(staff)))
                    .andExpect(jsonPath("$.items[0].firstName").value("عبدالله"))
                    .andExpect(jsonPath("$.items[0].lastName").value("سليم"));

            jdbc.update("UPDATE users SET review_status = 'APPROVED', review_reason = NULL WHERE id = ?",
                    perfect.id().toString());
            feed(viewer, "").andExpect(jsonPath("$.items[0].firstName").value("عبدالله"));
        }

        @Test
        void cardCarriesWhatTheFigmaCardShows() throws Exception {
            members.addPhoto(perfect.id(), "APPROVED", false, NOW.minusSeconds(500));
            UUID main = members.addPhoto(perfect.id(), "APPROVED", true, NOW.minusSeconds(400));
            members.addPhoto(perfect.id(), "PENDING_REVIEW", false, NOW.minusSeconds(300));
            members.addPhoto(perfect.id(), "REJECTED", false, NOW.minusSeconds(200));

            feed(viewer, "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(id(perfect)))
                    .andExpect(jsonPath("$.items[0].firstName").value("عبدالله"))
                    .andExpect(jsonPath("$.items[0].lastName").value("سليم"))
                    .andExpect(jsonPath("$.items[0].age").value(32))
                    .andExpect(jsonPath("$.items[0].location.countryCode").value("EG"))
                    .andExpect(jsonPath("$.items[0].location.region").value("القاهرة"))
                    .andExpect(jsonPath("$.items[0].location.city").value("المعادي"))
                    .andExpect(jsonPath("$.items[0].lookingFor.gender").value("FEMALE"))
                    .andExpect(jsonPath("$.items[0].lookingFor.ageMin").value(25))
                    .andExpect(jsonPath("$.items[0].lookingFor.ageMax").value(35))
                    .andExpect(jsonPath("$.items[0].presence.status").value("ONLINE"))
                    .andExpect(jsonPath("$.items[0].presence.lastActiveAt").value("2026-10-06T11:59:00Z"))
                    .andExpect(jsonPath("$.items[0].photoCount").value(2))
                    .andExpect(jsonPath("$.items[0].photoUrl").value(
                            org.hamcrest.Matchers.startsWith("/api/v1/photos/" + main + "/content?expires=")));
        }

        @Test
        void noPrivateFieldIsEverExposed() throws Exception {
            String body = feed(viewer, "").andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("email", "dateOfBirth", "date_of_birth", "password", "score",
                    "status\":\"ACTIVE", "storage", "session", "users/");
        }

        @Test
        void memberWithoutApprovedPhotoGetsAPlaceholder() throws Exception {
            members.addPhoto(perfect.id(), "PENDING_REVIEW", true, NOW);

            feed(viewer, "").andExpect(jsonPath("$.items[0].photoUrl").doesNotExist())
                    .andExpect(jsonPath("$.items[0].photoCount").value(0));
        }

        @Test
        void busyAndOfflinePresence() throws Exception {
            jdbc.update("UPDATE user_profiles SET presence_mode = 'BUSY' WHERE user_id = ?", id(perfect));
            jdbc.update("UPDATE users SET last_active_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf("2026-10-06 02:00:00"), id(oneWay));

            String body = feed(viewer, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.id == '" + id(perfect) + "')].presence.status"))
                    .containsExactly("BUSY");
            assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.id == '" + id(oneWay) + "')].presence.status"))
                    .containsExactly("OFFLINE");
            assertThat(JsonPath.<List<String>>read(body,
                    "$.items[?(@.id == '" + id(oneWay) + "')].presence.lastActiveAt"))
                    .containsExactly("2026-10-06T02:00:00Z");
        }
    }

    // ------------------------------------------------------------------------------------------------ pagination

    @Nested
    class Pagination {

        private List<String> walk(Member as, String query, int size) throws Exception {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                String url = query + (query.isEmpty() ? "" : "&") + "size=" + size
                        + (cursor == null ? "" : "&cursor=" + cursor);
                String body = feed(as, url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                List<String> page = JsonPath.read(body, "$.items[*].id");
                assertThat(page.size()).isLessThanOrEqualTo(size);
                seen.addAll(page);
                cursor = JsonPath.read(body, "$.hasMore") ? JsonPath.read(body, "$.nextCursor") : null;
                assertThat(++pages).isLessThan(1000);
            } while (cursor != null);
            return seen;
        }

        @Test
        void walkingAllPagesReturnsEveryMemberExactlyOnceInRankOrder() throws Exception {
            for (int i = 0; i < 37; i++) {
                int age = 25 + i % 20;
                long idleMinutes = (i % 7) * 600L;
                members.create(s -> { s.gender = "MALE"; s.age = age; s.lastActiveAt = NOW.minusSeconds(idleMinutes * 60); });
            }

            List<String> all = ids(viewer, "size=50");
            assertThat(all).hasSize(40);
            for (String sort : List.of("RELEVANCE", "RECENTLY_ACTIVE")) {
                List<String> walked = walk(viewer, "sort=" + sort, 7);
                assertThat(walked).hasSize(40);
                assertThat(new HashSet<>(walked)).hasSize(40);
                assertThat(walked).isEqualTo(ids(viewer, "sort=" + sort + "&size=50"));
            }
        }

        @Test
        void nearestPagination() throws Exception {
            members.locate(viewer.id(), 30.00, 31.00);
            for (int i = 0; i < 15; i++) {
                Member m = members.create(s -> s.gender = "MALE");
                members.locate(m.id(), 30.00 + (i % 5) * 0.01, 31.00); // ties on distance
            }
            List<String> walked = walk(viewer, "sort=NEAREST", 4);
            assertThat(walked).hasSize(15).doesNotHaveDuplicates();
        }

        @Test
        void lastPageHasNoCursor() throws Exception {
            feed(viewer, "size=3").andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
            feed(viewer, "size=2").andExpect(jsonPath("$.hasMore").value(true))
                    .andExpect(jsonPath("$.nextCursor").isString());
        }

        @Test
        void pagesStayConsistentWhileTimePasses() throws Exception {
            String first = feed(viewer, "size=1&online=true").andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(first, "$.nextCursor");
            clock.advance(Duration.ofMinutes(10)); // the cast is no longer "online now" by the wall clock

            String second = feed(viewer, "size=1&online=true&cursor=" + cursor).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> page = JsonPath.read(second, "$.items[*].id");
            assertThat(page).hasSize(1);
        }

        @Test
        void aCursorOnlyContinuesTheSearchItCameFrom() throws Exception {
            String body = feed(viewer, "size=1").andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(body, "$.nextCursor");

            feed(viewer, "size=1&ageMin=20&cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
            feed(viewer, "size=1&sort=RECENTLY_ACTIVE&cursor=" + cursor).andExpect(status().isBadRequest());
            // another member cannot reuse it either
            Member other = members.create(s -> s.gender = "FEMALE");
            feed(other, "size=1&cursor=" + cursor).andExpect(status().isBadRequest());
            // but a different page size is fine
            feed(viewer, "size=2&cursor=" + cursor).andExpect(status().isOk());
        }

        @Test
        void expiredOrForgedCursorsAreRejected() throws Exception {
            String body = feed(viewer, "size=1").andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(body, "$.nextCursor");

            feed(viewer, "size=1&cursor=not-a-cursor").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
            clock.advance(Duration.ofHours(2));
            feed(viewer, "size=1&cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }

        @Test
        void largeFeed() throws Exception {
            int count = 600;
            Set<String> created = new HashSet<>(List.of(id(perfect), id(oneWay), id(poor)));
            for (int i = 0; i < count; i++) {
                int age = 20 + i % 40;
                String sect = i % 3 == 0 ? "SUNNI" : i % 3 == 1 ? "SHIA" : null;
                created.add(members.create(s -> { s.gender = "MALE"; s.age = age; s.sect = sect;
                    s.lastActiveAt = NOW.minusSeconds((age * 37L) % 5000 * 60); }).id().toString());
            }
            List<String> walked = walk(viewer, "", 50);
            assertThat(walked).hasSize(count + 3).doesNotHaveDuplicates();
            assertThat(new HashSet<>(walked)).isEqualTo(created);
        }
    }
}
