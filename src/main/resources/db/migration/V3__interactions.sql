-- Interactions: likes (mutual like = match), favourites and profile views - the member profile's like / favourite
-- buttons, the Home counters and the Activity tab (النشاط). Rows disappear with either profile.

-- Likes: interest the liked member can see ("X liked you"); two likes in opposite directions are a match.
CREATE TABLE dashboard_likes (
    liker_id    CHAR(36)    NOT NULL,
    liked_id    CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_dashboard_likes PRIMARY KEY (liker_id, liked_id),
    CONSTRAINT fk_dashboard_likes_liker FOREIGN KEY (liker_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_likes_liked FOREIGN KEY (liked_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_likes_not_self CHECK (liker_id <> liked_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- "who liked me", newest first
CREATE INDEX ix_dashboard_likes_liked_created ON dashboard_likes (liked_id, created_at);
-- "my likes", newest first, and the daily like limit
CREATE INDEX ix_dashboard_likes_liker_created ON dashboard_likes (liker_id, created_at);

-- Favourites: private bookmarks; the other member never learns about them.
CREATE TABLE dashboard_favourites (
    owner_id    CHAR(36)    NOT NULL,
    member_id   CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_dashboard_favourites PRIMARY KEY (owner_id, member_id),
    CONSTRAINT fk_dashboard_favourites_owner FOREIGN KEY (owner_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_favourites_member FOREIGN KEY (member_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_favourites_not_self CHECK (owner_id <> member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_dashboard_favourites_owner_created ON dashboard_favourites (owner_id, created_at);
CREATE INDEX ix_dashboard_favourites_member ON dashboard_favourites (member_id);

-- Profile views: one row per (viewed member, viewer). WRITTEN BY user-profile-managment (ProfileViewRecorder) when a
-- member - never staff, never the owner - opens another member's profile; this service only reads it.
CREATE TABLE dashboard_profile_views (
    viewed_id        CHAR(36)    NOT NULL,
    viewer_id        CHAR(36)    NOT NULL,
    first_viewed_at  DATETIME(6) NOT NULL,
    last_viewed_at   DATETIME(6) NOT NULL,
    view_count       INT         NOT NULL,
    CONSTRAINT pk_dashboard_profile_views PRIMARY KEY (viewed_id, viewer_id),
    CONSTRAINT fk_dashboard_views_viewed FOREIGN KEY (viewed_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_views_viewer FOREIGN KEY (viewer_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_views_not_self CHECK (viewer_id <> viewed_id),
    CONSTRAINT ck_dashboard_views_count CHECK (view_count >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- "who viewed me", most recent first
CREATE INDEX ix_dashboard_views_viewed_last ON dashboard_profile_views (viewed_id, last_viewed_at);
CREATE INDEX ix_dashboard_views_viewer ON dashboard_profile_views (viewer_id);
