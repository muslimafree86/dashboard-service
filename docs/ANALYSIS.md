# Muslima Free — Dashboard / Member Discovery: Figma Analysis and Design

Source: Figma file `5PyysSqx1ScIFUxTjyxFSH` ("Muslima Free"), page **muslima website**, section `77:9298` — the same
authoritative section user-profile-managment was built from (see its `docs/ANALYSIS.md`). Frames used here: Home
`77:626` / `77:4049` (web) and `4:936` (older copy), logged-out Home `77:4826`, member profile `77:5577` / `65:4732`,
own profile `77:6922`.

---

## 1. What the Dashboard is

Muslima Free is an Arabic-first Muslim **matrimonial** platform. The "Dashboard" is the **Home page** a member lands on
after signing in: not an admin or analytics screen, but **personalised member discovery** — "which members should *this*
member see, and in which order?"

| Element on Home (77:626) | Text in the Figma | Meaning | Backend |
|---|---|---|---|
| Greeting + profile prompt | مرحبا ، عبدالله · اكمل ملفك الشخصي · 25% | Name, profile-completion | user-profile-managment `GET /me` (not duplicated here) |
| Search bar | ابحث عن [انثي] · العمر 20 – 45 · البلد مصر · المدينة القاهرة · علي مسافة __ كم · بحث | Sought gender (fixed), age range, country, city, distance in km | `GET /api/v1/dashboard/members?ageMin&ageMax&country&city&distanceKm`, pre-filled by `GET /api/v1/dashboard/filters` |
| Member cards | هند محمد · مصر ، المعادي ، القاهرة · ابحث عن : ذكر 31 - 47 · قبل 10 ساعات · 3 · online dot · photo/placeholder | Name, country/city/governorate, what *they* look for, last active, photo count, presence | `MemberCard` |
| Header tabs | الرئيسية · متصل الان · الشركاء · النشاط | Home · Online now · Partners · Activity | `online=true`, `mutual=true`; Activity = interactions module |
| Counters (4:936) | 5 · +9 | Favourites / views / likes / messages | interactions module (not discovery) |
| Card click | → member profile 77:5577 | Traits vs. "what they look for", green/red | user-profile-managment `GET /api/v1/members/{id}` |
| Profile page strip | اعضاء مشابهون | Similar members | `GET /api/v1/dashboard/members/{id}/similar` |

**Not in the Figma** (so not implemented): match percentage on cards, interests (the app has no interests — matching
is on matrimonial attributes), sort control, result count, like / hide / "not interested" buttons on cards, verification
badge.

## 2. Requirement gaps and the decisions taken

| # | Gap | Decision | Who decided |
|---|---|---|---|
| 1 | Brief asked for PostgreSQL/PostGIS; the platform runs on **MariaDB/MySQL** (Hostinger), chosen for user-profile-managment on 2026-09-11 | **Shared MariaDB.** Discovery reads the profile tables read-only (no copies, no sync; the one exception is presence, below) and owns only `dashboard_*` tables with its own Flyway history. Distance: lat/lon columns + bounding-box index + haversine in SQL (portable MySQL/MariaDB). | Product owner, 2026-10-06 |
| 2 | "علي مسافة __ كم" needs coordinates; profiles hold only free-text country / governorate / city | **Opt-in device location** (`PUT /api/v1/dashboard/me/location`), snapped to ~1 km before storage, never returned to others | Product owner, 2026-10-06 |
| 3 | No frame for the **الشركاء** (Partners) tab | **Mutual matches**: the member matches my preferences *and* I match theirs | Product owner, 2026-10-06 |
| 4 | Logged-out Home frame (77:4826) shows cards to visitors | **Members only**; the guest page is marketing. Discovery needs the viewer's gender/preferences/blocks and women's photos must not be public | Product owner, 2026-10-06 |
| 5 | How relevance is computed is not designed anywhere | **Proposed and implemented, configurable**: the nine attributes of the profile page's green/red table, in both directions, plus activity and proximity (README "Matching"). Weights `45/35/10/10` are a starting point to be tuned with product | Proposal — please confirm |
| 6 | The search field is "المدينة" (city) but cards show city *and* governorate ("المعادي، القاهرة") and the example search value "القاهرة" is a governorate | `city` matches the member's **city or governorate** | Proposal |
| 7 | "اعضاء مشابهون" has no definition | Same country, age ± 5 years, ranked by the number of shared attributes (governorate, marital status, education, children, smoking, sect, sector, appearance, body shape) | Proposal |
| 8 | Several matched attributes (education, sect, children, sector, weight) have **no input screen** in the Figma | An attribute a member left empty is *unknown*, never a contradiction: half credit in ranking, tolerated by the Partners tab | Proposal |
| 9 | Hide / "not interested" / already-liked exclusion | Built ("not interested", liked members out of daily picks); blocks (user-profile-managment) are applied everywhere | Product owner |
| 10 | Notifications bell (header) has no screen | Likes, matches, new messages; list + unread count + read | In the Figma |
| 11 | Daily picks, incognito browsing, report member — **not in the Figma** | Built on request; need UI | Product owner, 2026-10-06 |
| 12 | Distance search needs positions for members who never shared a location | Gazetteer: city / governorate centre from the profile, flagged approximate | Proposal, built |

## 3. Eligibility — mirrors user-profile-managment exactly

A card is only useful if it opens, so a member appears in discovery **exactly** when user-profile-managment's
`MemberVisibilityPolicy` would show their profile to the viewer: account `ACTIVE`, e-mail verified, onboarding
`COMPLETED`, profile `VISIBLE`, opposite gender, not the viewer, and no block in either direction. Both rule switches
(`opposite-gender-only`, `require-verified-email`) exist in both services and must stay equal.

The viewer must have finished onboarding (409 `ONBOARDING_INCOMPLETE`): their gender decides whom they see, and their age
and preferences drive the ranking.

## 4. Service boundary

```
user-profile-managment (owner)                 dashboard-service (reader)
  users, user_profiles, user_blocks,  ──read──►  eligibility, filters, ranking, cards
  profile_photos, user_sessions                  dashboard_member_locations (owned)
  issues JWT (RS256) ──────────────── public key / JWKS ──► validates the same tokens
  photo links (HMAC / CloudFront) ─── same signing material ─► signs card photo links
```

* Nothing in the profile tables is written or altered, except `users.last_active_at` (presence, with the profile
  service's own throttle and conditional update, README "Presence"); no index was added to them (measured
  unnecessary, README "Performance").
* The table snapshot in `src/test/resources/db/profile-schema/` is the contract; the integration tests run against it.
