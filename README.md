# Muslima Free — Dashboard (`dashboard`)

The **Home page** and **Activity tab** of Muslima Free: personalised member discovery, plus likes, matches, favourites,
profile views, messages between matches, notifications, daily picks, incognito browsing and member reports. Given the signed-in member, it decides which other members they may see, which of those match what
they look for (and whether they are what *those* members look for), and in which order — all inside the database, one
SQL statement per page.

Figma analysis, requirement gaps and decisions: [`docs/ANALYSIS.md`](docs/ANALYSIS.md).

## Stack

Java 21 · Spring Boot 3.5 · Spring Security (OAuth2 resource server, RS256) · Spring JDBC · MariaDB / MySQL (shared with
user-profile-managment) · Flyway · springdoc-openapi · JUnit 5 · Testcontainers.

## Code layout

One package per feature; each holds its own controller, service, repository, DTOs and enums, so everything about
e.g. messaging is in `messaging/`. Features talk to each other only through public services (`DiscoveryService`,
`NotificationService`, `ViewerService` ...).

```
com.muslimafree.dashboard
├── common/              infrastructure shared by all features
│   ├── config/          AppProperties (app.*), clock, OpenAPI, scheduling (ShedLock)
│   ├── security/        token validation, session check, per-member rate limit, presence (last active)
│   ├── exception/       error codes and RFC 7807 problem details (ar / en)
│   └── util/            cursor codec, time-ordered ids, SQL parameters
├── member/              who the viewer is, who may be seen (VisibilityRule), the member card
│   └── photo/           signed photo links (local HMAC / CloudFront)
├── discovery/           Home feed, tabs, search filters, relevance ranking, similar members
├── dailypicks/          today's picks
├── location/            nearby search: places, device location, search-position index and its job
├── hidden/              "not interested"
├── interaction/         likes, matches, favourites, Activity tab, Home counters
├── live/                the WebSocket: authentication, open sockets per member (features push through LiveSessions)
├── messaging/           conversations, messages, unread
├── notification/        the header bell
├── report/              member reports and the moderators' queue
└── settings/            dashboard settings (incognito)
```

Tests follow the same packages (`src/test/java/.../messaging/MessagingIT` ...); `support/` holds the shared
integration-test base, member fixtures, test tokens and clock.

## Run locally

Start user-profile-managment first (it owns the schema and issues the tokens), then:

```bash
DB_HOST=localhost DB_PORT=3307 DB_DATABASE=muslima_users DB_USERNAME=muslima DB_PASSWORD=muslima \
JWT_JWK_SET_URI=http://localhost:8081/.well-known/jwks.json JWT_ISSUER=https://auth.muslimafree.com \
PHOTO_URL_SIGNING_KEY=<same value as user-profile-managment> \
mvn spring-boot:run          # http://localhost:8082, Swagger UI at /swagger-ui.html
```

| Variable | Purpose | Default |
|---|---|---|
| `DB_URL` or `DB_HOST`/`DB_PORT`/`DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DRIVER` | The **same** database as user-profile-managment; the password comes from `DB_PASSWORD` or the git-ignored `.env` | Hostinger dev DB: srv1907.hstgr.io:3306/u289637970_muslima_dev_db, user u289637970_muslima_user |
| `DB_POOL_SIZE` | Connections; shared hosting limits connections per DB user across both services | 5 |
| `JWT_JWK_SET_URI` *or* `JWT_PUBLIC_KEY_LOCATION` | user-profile-managment's JWKS endpoint, or its PEM public key | — (one is required) |
| `JWT_ISSUER` | Must equal user-profile-managment's `app.security.jwt.issuer` | https://auth.muslimafree.com |
| `PHOTO_STORAGE` | `local` or `s3`, as in user-profile-managment | local |
| `PHOTO_URL_SIGNING_KEY` | `local`: the profile service's HMAC key (startup fails without it) | — |
| `CLOUDFRONT_DOMAIN`, `CLOUDFRONT_KEY_PAIR_ID`, `CLOUDFRONT_PRIVATE_KEY_LOCATION` | `s3`: the profile service's CloudFront signing material | — |
| `APP_ALLOWED_ORIGINS` | CORS when called directly (the gateway normally decides CORS) | http://localhost:3000 |

Business rules live in `application.yml` under `app.discovery`, `app.location`, `app.rate-limit`, `app.interactions`,
`app.messaging`, `app.reports`, `app.daily-picks`, `app.jobs` (`JOBS_ENABLED=false` turns the scheduled jobs off).

### API gateway

`api-gateway-service` routes `/api/v1/dashboard/**` here (route `dashboard`, `DASHBOARD_URL`) and serves this service's
OpenAPI document at `/v3/api-docs/dashboard` in its Swagger UI; it also health-checks `/actuator/health`. Card photo
links point at `/api/v1/photos/...`, which the gateway routes to user-profile-managment.

## API (`/api/v1/dashboard`, signed-in members only)

| Endpoint | Screen | Notes |
|---|---|---|
| `GET /members` | Home feed (الرئيسية), Online now (`online=true`), Partners (`mutual=true`), search bar, advanced search | Search bar: `ageMin`, `ageMax` (18–99), `country` (ISO-2), `city`, `distanceKm` (1–500), `near` (a place id: measure distances from another city). Advanced: `maritalStatus`, `sect`, `smoking` (repeatable or comma-separated), `educationMin`, `children`, `nationality` (ISO-2), `withPhoto`. `sort` (`RELEVANCE` default, `RECENTLY_ACTIVE`, `NEAREST`, `NEWEST`), `size` (1–50, default 20), `cursor`. Different filters combine (AND), values of one filter are alternatives (OR). |
| `GET /members/{memberId}/similar` | "اعضاء مشابهون" under a member's profile | 404 when the caller may not see that member |
| `GET /filters` | Search bar initial values | From the caller's partner preferences, plus field limits and where my distances are measured from (`locationSource`, `locationPlace`) |
| `GET /places?q&country&limit` | City field / "search near…" | Autocomplete over governorates and cities (Arabic or English, any spelling) |
| `GET /daily-picks` | Today's picks | Up to 10, fixed for the UTC day; mutual matches first; never someone liked, hidden or picked in the last 7 days |
| `PUT /me/location` · `GET /me/location` · `DELETE /me/location` | Opt-in device location | Snapped to ~1 km; at most 20 moves a day (429 `LOCATION_CHANGE_LIMIT_REACHED`, also under parallel requests); `active` / `expiresAt` say when it stops counting (ask the member for a fresh one); GET answers 204 when none is shared |
| `GET /me/settings` · `PUT /me/settings` | Dashboard settings | `{incognito}`: my profile views are not recorded |
| `GET /notifications?page&size` · `GET /notifications/unread` · `PUT /notifications/read` · `PUT /notifications/{id}/read` | Header bell | `LIKE_RECEIVED`, `MATCH`, `NEW_MESSAGE`, with the actor's card; pushed over `WS /live` as they happen, else poll the unread count |
| `POST /reports/{memberId}` | Report a member | `{reason, context, details}`; always 202; also hides the member from me; 10 a day |
| `GET /admin/reports?status&reportedId` · `PATCH /admin/reports/{id}` | Moderation queue (MODERATOR / MANAGER / ADMIN) | Oldest open first, with open reports per member; close as `ACTIONED` / `DISMISSED` with a note |
| `PUT /hidden-members/{id}` · `DELETE /hidden-members/{id}` · `GET /hidden-members?page&size` | "Not interested" (pass) and its undo list | One-way and silent, unlike a block; 404 for a member the caller cannot see; list newest first |
| `PUT /likes/{id}` · `DELETE /likes/{id}` | Like button on a member's profile | `{matched}`: true when they had liked me first. Idempotent; unlike ends a match. Daily limit (100) → 429 `DAILY_LIKE_LIMIT_REACHED` |
| `PUT /favourites/{id}` · `DELETE /favourites/{id}` | Favourite button | Private bookmark; idempotent |
| `GET /members/{id}/interactions` | Button state on a member's profile | `{liked, likedYou, matched, favourite}` |
| `GET /activity` | Home counters (4:936) | `{favourites, profileViews, likesReceived, matches, unreadMessages}` |
| `GET /activity/{list}?page&size` | Activity tab (النشاط) | `list` = `likes-received`, `likes-sent`, `matches`, `favourites`, `viewers`; most recent first |
| `GET /conversations?box&page&size` | Messages page (68:1058): الواردة / المرسلة | `box` = `INBOX` (default: last message received), `SENT` (last message mine), `ALL`; member card, preview, time, unread count, `canSend` |
| `GET /conversations/unread` | Messages badge | `{unreadMessages, unreadConversations}`; poll every ~30 s |
| `GET /conversations/{memberId}/messages?before&after&limit` | Chat thread, mini chat (68:1856) | Oldest first. No cursor = latest; `before=<messageId>` = older history; `after=<messageId>` = poll for new messages every few seconds |
| `POST /conversations/{memberId}/messages` | اكتب رسالتك | `{body}` (1–2000 chars, plain text). Matches only → else 403 `NOT_MATCHED`; 30/minute → 429 `MESSAGE_RATE_LIMITED` |
| `PUT /conversations/{memberId}/read` | Opening a chat | Clears its unread count (sending a message also does) |
| `WS /live` | Chat, mini chat, bell, badges | Real-time push of new messages, reads and bell notifications — see [Real-time delivery](#real-time-delivery-websocket) |

The owner of a dashboard is always the token's subject; no endpoint accepts a viewer id. Opening a card uses
user-profile-managment's `GET /api/v1/members/{id}` (traits, partner preferences, green/red compatibility) — discovery
eligibility mirrors its visibility rule, so every card opens.

```json
GET /api/v1/dashboard/members?ageMin=28&ageMax=40&country=EG&size=20
{
  "items": [{
    "id": "7f3c2a9e-…", "firstName": "عبدالله", "lastName": "سليم", "age": 32,
    "location": {"countryCode": "EG", "region": "القاهرة", "city": "المعادي"},
    "lookingFor": {"gender": "FEMALE", "ageMin": 25, "ageMax": 35},
    "presence": {"status": "ONLINE", "lastActiveAt": "2026-10-06T11:59:00Z"},
    "photoUrl": "/api/v1/photos/…/content?expires=…&signature=…", "photoCount": 2,
    "distanceKm": 9
  }],
  "nextCursor": "djF8…", "hasMore": true
}
```

Errors are RFC 7807 problem details with a stable `code`, localized by `Accept-Language: ar|en`:
`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_AGE_RANGE`, `INVALID_CURSOR`, `PAGE_OUT_OF_RANGE` (400) ·
`UNAUTHENTICATED` (401) · `ACCESS_DENIED` (403) · `MEMBER_NOT_FOUND` (404) · `ONBOARDING_INCOMPLETE`, `LOCATION_REQUIRED`
(409) · `TOO_MANY_REQUESTS` (429, with `Retry-After` and `retryAfterSeconds`).

## Discovery rules

```
token → viewer (profile, preferences, location) → eligible members → filters → candidate pool → score → order → page → cards
```

1. **Viewer**: from the access token only; must have finished onboarding.
2. **Eligibility** (identical to user-profile-managment's `MemberVisibilityPolicy`): ACTIVE account, verified e-mail,
   onboarding COMPLETED, profile VISIBLE, opposite gender, not the viewer, no block in either direction — and not
   marked "not interested" by the viewer.
3. **Filters**: the Figma search bar (age range, country, city-or-governorate, distance), the tabs (online now,
   mutual) and advanced search on attributes every profile has (marital status, minimum education, sect, smoking,
   children, nationality, has an approved photo). Attribute filters are strict: a member who left the attribute empty
   does not match it.
4. **Candidate pool**: the `candidate-pool` (1000) members that pass 2 and 3, most recently active first (most recently
   joined first for `NEWEST`).
5. **Ranking** of the pool, then keyset pagination.

## Matching

The score compares the **same nine attributes** the member profile page shows as green/red cells (user-profile-managment
`CompatibilityCalculator`): age, country, education (minimum level), children, smoking, sect, employment sector,
appearance, body shape. Four signals, each 0..1:

| Signal | Weight | Definition |
|---|---|---|
| Preference fit | 45 | Candidate vs. what the **viewer** looks for. Mean over the preferences the viewer stated: match 1, not filled in 0.5, contradiction 0. No preferences = 1. |
| Reciprocal fit | 35 | Viewer vs. what the **candidate** looks for — exactly the green/red cells the viewer sees on the candidate's profile. Same scale. |
| Activity | 10 | Online now 1 · last 24 h 0.7 · 7 days 0.4 · 30 days 0.1 · older 0 |
| Proximity | 10 | Same governorate 1 · same country 0.5 · else 0 |

`score = round(1000 × weighted mean)`, an integer so that pagination comparisons are exact. It is deterministic (the
clock is fixed per scroll session), explainable (every point traces to a profile cell), configurable
(`app.discovery.weights`) and **not exposed** — the Figma shows no match percentage. Weights are a proposal to tune with
product (docs/ANALYSIS.md gap 5).

**Partners (`mutual=true`)**: no attribute contradicts a preference, in either direction. Unfilled attributes are not
contradictions (several have no input screen yet).

**Similar members**: same country and age ± 5 years as the member whose profile is open, ordered by the number of shared
attributes, then score.

## Interactions (Activity)

* **Like** — interest the liked member sees ("X liked you"). Two likes in opposite directions are a **match**
  (`matched=true` in the like response, the `matches` list); matches are what messaging will be gated on. Liking a member
  also takes them out of "not interested". At most `app.interactions.daily-like-limit` (100) new likes per UTC day.
* **Favourite** — a private bookmark; the other member never sees it, not even in counts.
* **Profile views** — written by **user-profile-managment** when a member (never staff, never the owner) opens another
  member's profile: one row per viewer and viewed member (`dashboard_profile_views`, first/last view, count). This
  service only reads them for `viewers` and the `profileViews` counter. The profile service switch is
  `RECORD_PROFILE_VIEWS`.
* Every action, list and counter applies the discovery visibility rule: one can only like, favourite or see in a list a
  member one may currently see. Rows of a member who becomes invisible (blocks, hides their profile, is suspended) are
  kept and reappear if they become visible again; they are deleted with either profile.
* Lists are page/size (newest first, 10 000 rows reachable), counters are one indexed `COUNT` each.

## Nearby search

Distance search works for **every** member, not only those who shared a phone location:

| Search position of a member | Precision | Distances shown |
|---|---|---|
| Device location, shared and younger than 30 days (`app.location.device-max-age`) | ~1 km grid | whole km, at least 1 |
| Otherwise the centre of their city / district (from the profile's free-text city) | city | rounded to 5 km, at least 5, `distanceApproximate: true` |
| Otherwise the centre of their governorate | governorate | idem |
| Unknown place | none | not found by distance; found by every other search |

* **Gazetteer** (`dashboard_places`, migrations V5 and V7, ~215 places): Egypt's 27 governorates (centre = capital)
  with their main cities and districts, plus the main cities of every Gulf, Levant, Iraqi, North-African country and of
  the diaspora (Europe, North America, Australia, Malaysia), in Arabic and English with aliases. Matching ignores diacritics, hamza
  forms, ta marbuta, "ال"/"El-", "محافظة" and case (`PlaceNames`), so "الاسكندريه", "محافظة الإسكندرية" and
  "Alexandria" all resolve. Add places with new migrations.
* **Search index** (`dashboard_search_locations`): one position per member, kept by `LocationIndexer`:
  * immediately when a member shares or removes a device location;
  * every 30 s (`app.location.sync-interval`) for members whose country / governorate / city changed — user-profile-managment
    queues them in `dashboard_location_changes` (its `PlaceChangeRecorder`, `app.interactions.record-place-changes`) —
    and for device locations that just went stale (index on their date);
  * every 6 h a full pass (`app.location.reconcile-interval`) as a safety net only. Both jobs use ShedLock.
* **My own centre** is computed live the same way (or is the place given in `near`), so a viewer is never stuck on stale
  data. 409 `LOCATION_REQUIRED` only when no position at all is known.
* **Ranking**: the proximity signal uses the real distance when both positions are known (≤ 10 km 1, 25 km 0.8, 50 km
  0.6, 100 km 0.4, 300 km 0.2), otherwise same governorate / country.
* **Anti-tracking**: device positions snapped to ~1 km before storage, whole-km distances never below 1, approximate
  distances in 5 km steps, at most 20 device moves a day (re-sending the same position is free; one atomic statement,
  so parallel requests cannot exceed it) — moving one's own position around and reading distances is how someone
  else's position is triangulated. The radius filter, the `NEAREST` order and its cursor use **the rounded distance the
  card shows**, never the exact one, so none of them tells more than the card (members at the same shown distance are
  ordered by relevance). With `near` (any city centre, unlimited) every distance is approximate, i.e. 5 km steps.
* **Nearest order**: the candidate pool is the closest members, not the most active ones, so a nearby member who was
  last active weeks ago is still first. The service searches circles of 10, 50, 200 and 1000 km beyond the previous
  page's distance and stops at the first that fills the page: the cost is the number of members in that circle, small
  in a dense city; only a nearly empty map reads every position.
* **Index choice, measured**: a geohash index was benchmarked against the (latitude, longitude) B-tree on 400 000
  positions, 60 % in greater Cairo; it was not faster at any radius (5–100 km) because the cost is the number of members
  inside the circle, which the candidate pool already bounds. The B-tree stays.

## Notifications, daily picks, incognito, reports

* **Notifications** (header bell): written when a member is liked, matched (both sides; listed above the like that
  caused it) or messaged (one per conversation, refreshed by each message, read when the chat is read). A repeated
  event moves its notification to the top instead of duplicating it; taking back a like removes its notifications.
  Only notifications from members the caller may currently see are listed or counted. Push / e-mail delivery can read
  the same rows later.
* **Daily picks**: chosen at the first request of the UTC day and stored, so the list is the same all day; mutual
  matches first, then the best relevance scores; never members already liked, marked "not interested" or picked in
  the last `app.daily-picks.history` (7) days. Old picks are deleted nightly.
* **Incognito** (`dashboard_member_settings.incognito`): user-profile-managment's view recorder skips the viewer in the
  same SQL statement, so incognito members never appear in "who viewed me". Likes, favourites and messages are
  deliberate and stay visible.
* **Reports**: a reason (fake profile, inappropriate photos, harassment, scam, offensive messages, not serious,
  underage, spam, inappropriate content, other), a context (profile, photo, message) and an optional note go to the
  moderation queue; the reported member disappears from the reporter's discovery. The answer is the same whether or
  not the member exists. Reports survive the reporter's account deletion (reporter set to null).
  `dashboard_reports` is the platform's **only** report store: admin-managment-service's moderation screens
  (`/api/v1/admin/reports`, with most-reported members and suspend / ban decisions) read and close the same rows,
  and the columns they record (`message_id`, `action_taken`, `updated_at`, `version`) come from migration V8. Its
  migration V1008 moved its former `admin_user_reports` rows here, so run this service's migrations before it.
  The queue below (`/api/v1/dashboard/admin/reports`) works on the same rows; moderators act on the account with
  user-profile-managment's admin API.

## Messaging

* **Matches only**: a member can write to another only while both like each other — the match is the recipient's
  consent. When either takes back their like, the history stays readable but closed (`canSend=false`).
* **Visibility**: a conversation is shown only while the other member is visible under the discovery rule, so a block
  hides it from both sides (nothing is deleted; it returns if the block is lifted). Deleting either profile deletes
  the conversation and its messages.
* **Delivery**: pushed over a WebSocket (below); REST stays the source of truth, so a client that reconnects or
  misses a frame catches up with `after=<last message id>`. Messaging is exempt from the browsing rate limit and has
  its own send limit (`app.messaging.max-per-minute`, 30).
* **Storage**: one row per conversation, one per member side (last message preview, unread count, last read — what the
  inbox shows, without reading messages), and messages with time-ordered (UUIDv7) ids. The inbox is one indexed query per
  page; a thread page is one range read on `(conversation_id, created_at, id)`.
* Text only, as in the Figma. Message bodies are stored as typed (trimmed) and returned as plain text: clients must
  never render them as HTML.

### Real-time delivery (WebSocket)

`wss://<host>/api/v1/dashboard/live` — one socket per tab or device. Sending stays `POST /conversations/{memberId}/messages`
(match check, visibility and send limit in one place); the socket only pushes.

1. Connect (no token on the handshake: browsers cannot set `Authorization` on a WebSocket, and a token in the URL would
   land in access logs).
2. First frame within 10 s: `{"type":"auth","token":"<access token>"}` → `{"type":"ready"}`. Checked by the same
   decoder as REST: signature, issuer, expiry, live session, role `MEMBER`.
3. Server frames, sent only after the database commit:
   * `{"type":"message","memberId":"<other member>","message":{"id","fromMe","body","sentAt"}}` — to the recipient and
     to the sender's other tabs/devices (`fromMe=true`). Same `message` shape as a thread; `memberId` as in the REST paths.
   * `{"type":"read","memberId":"<other member>"}` — the member read that chat on another device: clear its badge.
     Not sent to the writer (no read receipts).
   * `{"type":"notification","notification":{"id","type","actor","at","read"},"unreadNotifications":3}` — a new or
     refreshed bell row (`LIKE_RECEIVED`, `MATCH`, `NEW_MESSAGE`), exactly as `GET /notifications` lists it, with the
     new bell count. A new message therefore sends both a `message` frame (the chat) and a `notification` frame (the
     bell); repeated messages refresh one row, so its `id` stays the same. Taken-back likes and reads on another
     device are not pushed: the bell list corrects itself on its next fetch.
4. Client: `{"type":"ping"}` → `{"type":"pong"}` every ~25 s keeps proxies from idling the socket out. Send a new
   `auth` frame with a refreshed token before the old one expires (same member only).

Close codes: `4401` not authenticated / token expired / session revoked (refresh the token and reconnect), `4403` no
`MEMBER` role, `4429` more than 5 sockets for one member, `1008` auth frame for another member, `1007` malformed frame.
Every 10 s a sweep closes sockets whose token expired or whose session was revoked, so a logout or suspension reaches
an open socket within ~40 s (sweep + session cache).

On (re)connect a client fetches `/conversations/unread`, `/notifications/unread` and, for an open chat,
`after=<last id>` once, then relies on pushes. An open socket counts as activity: its `auth` and `ping` frames keep the
member "online now" (below). The gateway must forward WebSocket upgrades on `/api/v1/dashboard/live`.

**One instance only, for now**: sockets live in the memory of the instance that accepted them, and a send is pushed
from the instance that stored it. With several instances, members connected to another instance only see the message
on their next REST call — run one instance, or add a shared relay (Redis pub/sub, or a broker) before scaling out.

## Ordering and pagination

| `sort` | Order |
|---|---|
| `RELEVANCE` (default) | score ↓, last active ↓, id |
| `RECENTLY_ACTIVE` | last active ↓, score ↓, id |
| `NEAREST` | shown distance ↑, score ↓, id (needs the viewer's position; lists members with one) |
| `NEWEST` | joined ↓, score ↓, id ("new members") |

Keyset (cursor) pagination: `nextCursor` encodes the last row's sort key, the clock of the first page and a fingerprint
of the viewer + filters. Every order ends on the member id, so a member never appears twice. Cursors only continue the
search they came from and expire after `cursor-ttl` (1 h) → 400 `INVALID_CURSOR`. No total count is returned (it would
cost as much as the search). Scores can only rise during a scroll session (activity), so a member who becomes active may
be skipped, never duplicated.

## Presence ("متصل الان")

Every authenticated request and every socket `auth` / `ping` frame refreshes `users.last_active_at`, exactly like
user-profile-managment's `LastActiveFilter`: at most one write a minute per member (`app.security.last-active-write-interval`,
the same 60 s) and the same conditional `UPDATE … WHERE last_active_at < now - interval`, so the two services together
never write more than one would. It is the only write this service makes to a profile table; rejected tokens never
count. Without it a member who only browses or chats here would show as offline after `online-window` (5 min).

## Security and privacy

* RS256 tokens of user-profile-managment, issuer checked, and — like that service — the token's session is checked on
  every request against `user_sessions` (revoked, expired, wrong subject, account not ACTIVE → 401), cached 30 s.
* Cards expose only what other members already see on a profile page: name, **age (never the date of birth)**,
  country/governorate/city, what they look for, presence at minute precision, approved photos only, photo count.
  Never: e-mail, date of birth, account status, score, storage keys, coordinates.
* **Names wait for review**: until staff approve a member (`users.review_status = APPROVED`, set by
  user-profile-managment), every card shows "New user" / "مستخدم جديد" (`member.pendingName`) and an empty last name;
  age, place and photos stay visible. Staff callers (MODERATOR, MANAGER, ADMIN) see the real name - the same rule as
  the member profile page.
* **Location**: snapped to a 0.01° grid (~1 km) *before* storage; other members see only whole kilometres, never below 1
  ("0 km" would mean "same cell"); readable only by its owner; deleted with the profile (FK cascade on account purge).
* Photo links are signed and short-lived and issued only for members that passed eligibility — the same rule as the
  profile service ("authorization where the link is issued").
* Hidden, blocked, unknown and same-gender members are indistinguishable (404 on `/similar` and on hiding).
* **Rate limit** per member: 60 requests a minute and 2000 a day (`app.rate-limit`), so an account cannot script-copy
  members' profiles and photos. Requests turned away by the minute limit do not use up the daily budget. Counters are
  in memory per instance (N instances → up to N× the budget); a shared counter is only needed if limits must be exact.

## Performance

Measured on stock MariaDB 11.4 (default configuration), 100 000 members, ~43 000 eligible for the viewer:

| Query | Before (rank everyone) | Now (two-stage) | Plan |
|---|---|---|---|
| Home feed | ~520 ms | **~30 ms** | walks `ix_users_last_active`, stops at the pool size |
| Online now | — | ~1 ms | `ix_users_last_active` range |
| Within 10 km | — | ~12 ms | `ix_dashboard_locations_lat_lon` bounding box, then haversine |
| Search country + age (rare) | — | ~1 ms | `ix_profiles_gender_country_dob` |
| Search EG + Cairo + 28–35 | — | ~130 ms | `ix_profiles_gender_country_dob`, 15 k rows |
| Partners (mutual) | — | ~220 ms | `ix_profiles_gender_country_dob`, 30 k rows |

Why two stages: scoring is per row, and *any* plan that visits every eligible member costs time proportional to the
member count (two primary-key lookups each) — half a second at 43 k, seconds at 1 M. The candidate pool bounds the
ranking to 1000 rows; ordering the pool by `(last_active_at, id)` matches an existing index, so the database stops
reading as soon as the pool is full. Trade-off: a feed ends after 1000 members (50 pages); members inactive for long are
reached through filters. Everything else is index-driven; card photos are one batched query per page. No index was added
to user-profile-managment's tables — the measurements showed its indexes suffice.

Testcontainers' bundled MariaDB configuration (16 MB buffer pool, 64 KB sort buffer) is 10–25× slower; do not read
timings from the test suite.

## Tests

`mvn verify` — unit tests (`*Test`) and integration tests (`*IT`) on MariaDB 11.4 via Testcontainers; `-Dit.db=mysql`
runs them on MySQL 8.4. Integration tests apply user-profile-managment's real migrations
(`src/test/resources/db/profile-schema`, its own history table) before this service starts, i.e. the production order.

| Area | Covered |
|---|---|
| Eligibility | hidden, unverified, onboarding incomplete, suspended/deactivated/pending deletion, same gender, blocked both ways, self, viewer not onboarded, empty feed |
| Ranking | two-way best match first, reciprocity, activity tie-break, unknown between match and contradiction, determinism, recently-active order, pool cut |
| Filters | age (inclusive bounds), country, city-or-governorate, combinations, online, mutual (incl. unknowns), SQL injection, invalid values, localized errors |
| Distance | radius, members without location, nearest order, rounding ≥ 1 km, no coordinates in responses, location required, combined filters |
| Cards | every Figma field, approved photos only, main photo first, placeholder, BUSY/OFFLINE presence, no private fields |
| Pagination | every member exactly once in rank order (3 sorts), last page, stable clock across pages, cursor bound to search and viewer, expired/forged cursors, 600-member walk |
| Security | anonymous, wrong issuer/key, expired, revoked session, suspended account, borrowed session, missing role, caller-chosen owner, public health/docs |
| Location / similar / defaults | snapping, CRUD, validation, cascade on purge; similarity order, age/country window, eligibility, 404s; pre-filled search bar |
| Not interested | hidden from feed/tabs/search/similar, one-way, idempotent, undo, 404 for invisible members, list order and paging, cascade |
| Advanced filters / newest | each filter strict on unfilled attributes, multi-value OR, combinations, approved photos only, newest order and paging, invalid values, cursor bound to filter values |
| Nearby | city/governorate fallback for members without device location (Arabic spellings, "محافظة", unknown districts), device wins over city, stale device falls back, profile edits reach the index, `near` another city, closer ranks higher, daily move cap (same position free), share/remove updates search at once, place autocomplete (Arabic/English, country filter, not rate limited); 14 name-normalisation cases; nearest beyond the activity pool and across pages, cursor holds only the shown distance, radius on the shown distance, 10 parallel moves vs the daily cap, queued place changes and stale devices applied by the incremental pass, `active`/`expiresAt`, places outside Egypt |
| Notifications | like then match (both sides, order), taking back a like, no duplicates, one message notification per conversation until read, read one / all, other members' ids, invisible actors hidden |
| Reports | queue content and per-member count, close as ACTIONED/DISMISSED, members cannot see the queue, no existence leak, validation and daily cap, report survives reporter deletion |
| Incognito / picks | incognito viewers not recorded (the profile service's exact statement), on/off; picks stable all day, mutual first, no repeats within history, liked never again, hidden removed at once |
| Rate limit | minute and day windows, Retry-After, per-member budgets, rejected requests not counted daily, anonymous calls rejected first |
| Messaging | matched members exchange messages, both points of view, matches only (one-way like refused), unlike closes but keeps history, block hides for both, inbox/sent tabs by last writer, unread counts and reading, history paging and polling, cursor from another conversation refused, send limit, polling exempt from browsing limit, validation, preview (Arabic, single line, 120 chars), 404 for invisible members, cascade |
| Presence | dashboard requests and open sockets keep a member online, one write per interval, rejected tokens do not count |
| Real-time chat | bell notifications pushed (like, match to both, new message refreshing one row) with the unread count; message pushed to the recipient and the sender's other devices, nothing for others or refused sends, read pushed to the reader's other devices only, ping, bad / expired / foreign-key / wrong-issuer / revoked tokens (4401), missing role (4403), frames before auth, switching member, token refresh, auth timeout, sweep of revoked sessions and expired tokens, 5 sockets per member |
| Interactions | like seen by the liked member, mutual like = match, idempotency, unlike ends a match, private favourites, who viewed me (profile service's exact upsert), counters and lists drop invisible/blocked members without deleting, 404 for invisible members, liking undoes "not interested", daily like limit, paging, cascade |

## Recommended next steps (deliberately not built yet)

* **Product confirmation** of the score weights, the city-or-governorate rule and the "similar" definition.
* A dashboard Postman folder.
* **Push / e-mail delivery** of notifications (the rows exist; needs FCM/APNs or e-mail plumbing).
* **"New" badges** on the Activity lists (seen-until timestamps per list).
* **More places** in the gazetteer (all Egyptian cities, more countries), or geocoding of free-text cities.
* **Report-driven automation**: auto-hide a member from everyone after N open reports, pending moderator review.
* **Cross-instance real-time delivery** (Redis pub/sub or a broker behind `LiveSessions.send`) before running more
  than one instance; today a socket only hears messages stored by its own instance.
* **Read receipts, typing indicator, attachments** — not in the Figma.
* **Report a message / conversation** and **wali (guardian) access** to conversations — trust & safety features to
  decide with product.
* **Contact-detail filtering** in first messages (phone numbers, links), a common anti-scam measure.
* **Optionally leave liked members out of the feed** (one `NOT EXISTS`, like hidden members) — a product choice.
* **Precomputed match features** (a per-member bitmask maintained on profile change) if mutual/search queries outgrow
  ~200 ms — needs a change event from user-profile-managment, which has no outbox yet.
* **Caching** the first page per viewer for a minute if Home traffic dominates the database.
* **Location freshness**: ignore locations older than N days, and ask the client to refresh on app start.
* **Geocoded governorates** (reference table) so distance works without device location.
