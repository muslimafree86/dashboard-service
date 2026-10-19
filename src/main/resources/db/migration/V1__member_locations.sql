-- Muslima Free - dashboard (member discovery). MySQL 8.0.16+ / MariaDB 10.6+.
--
-- This service shares the database of user-profile-managment and reads its member tables (users, user_profiles,
-- user_blocks, profile_photos, user_sessions) without ever writing them or changing their schema: the discovery query
-- is served by that service's existing indexes (ix_users_last_active, ix_profiles_gender_country_dob, primary keys).
-- The only data it owns is listed here; every object it creates is prefixed dashboard_ and its migration history is
-- dashboard_flyway_history.

-- ---------------------------------------------------------------------------
-- Opt-in device location for "علي مسافة __ كم" (within N km)
--
-- Coordinates are snapped to a ~1 km grid (app.location.grid-decimals) BEFORE they are stored, so the exact position
-- a phone reported never reaches the database. They are never returned to other members: only rounded distances are.
-- The row disappears with the profile (account purge deletes user_profiles -> ON DELETE CASCADE).
-- ---------------------------------------------------------------------------
CREATE TABLE dashboard_member_locations (
    user_id     CHAR(36)     NOT NULL,
    latitude    DECIMAL(7,4) NOT NULL,
    longitude   DECIMAL(8,4) NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_dashboard_member_locations PRIMARY KEY (user_id),
    CONSTRAINT fk_dashboard_locations_profile FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_locations_lat CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_dashboard_locations_lon CHECK (longitude BETWEEN -180 AND 180)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- distance search pre-filters on a bounding box (latitude range first, then longitude) before the exact haversine
CREATE INDEX ix_dashboard_locations_lat_lon ON dashboard_member_locations (latitude, longitude);
