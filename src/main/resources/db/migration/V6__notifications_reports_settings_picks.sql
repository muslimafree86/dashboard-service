-- Notifications (the header bell), member reports (moderation queue), member settings (incognito) and daily picks.

-- One notification per (member, type, actor): a repeated event moves it to the top and marks it unread again instead
-- of piling up duplicates. Time-ordered ids.
CREATE TABLE dashboard_notifications (
    id          CHAR(36)    NOT NULL,
    member_id   CHAR(36)    NOT NULL,
    type        VARCHAR(20) NOT NULL,
    actor_id    CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    read_at     DATETIME(6) NULL,
    CONSTRAINT pk_dashboard_notifications PRIMARY KEY (id),
    CONSTRAINT uk_dashboard_notifications_event UNIQUE (member_id, type, actor_id),
    CONSTRAINT fk_dashboard_notifications_member FOREIGN KEY (member_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_notifications_actor FOREIGN KEY (actor_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_notifications_type CHECK (type IN ('LIKE_RECEIVED', 'MATCH', 'NEW_MESSAGE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_dashboard_notifications_member_created ON dashboard_notifications (member_id, created_at);
CREATE INDEX ix_dashboard_notifications_actor ON dashboard_notifications (actor_id);

-- Member reports for moderators. Kept when the reporter deletes their account (reporter_id set NULL) so evidence
-- survives; removed with the reported profile (the account itself is gone).
CREATE TABLE dashboard_reports (
    id               CHAR(36)      NOT NULL,
    reporter_id      CHAR(36)      NULL,
    reported_id      CHAR(36)      NOT NULL,
    reason           VARCHAR(30)   NOT NULL,
    context          VARCHAR(20)   NOT NULL,
    details          VARCHAR(1000) NULL,
    status           VARCHAR(20)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    reviewed_by      CHAR(36)      NULL,
    reviewed_at      DATETIME(6)   NULL,
    resolution_note  VARCHAR(1000) NULL,
    CONSTRAINT pk_dashboard_reports PRIMARY KEY (id),
    CONSTRAINT fk_dashboard_reports_reporter FOREIGN KEY (reporter_id) REFERENCES user_profiles (user_id) ON DELETE SET NULL,
    CONSTRAINT fk_dashboard_reports_reported FOREIGN KEY (reported_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_reports_reason CHECK (reason IN ('FAKE_PROFILE', 'INAPPROPRIATE_PHOTOS', 'HARASSMENT', 'SCAM',
        'OFFENSIVE_MESSAGES', 'NOT_SERIOUS', 'UNDERAGE', 'OTHER')),
    CONSTRAINT ck_dashboard_reports_context CHECK (context IN ('PROFILE', 'PHOTO', 'MESSAGE')),
    CONSTRAINT ck_dashboard_reports_status CHECK (status IN ('OPEN', 'ACTIONED', 'DISMISSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- moderation queue: open reports, oldest first; per-member history; the reporter's daily limit
CREATE INDEX ix_dashboard_reports_status_created ON dashboard_reports (status, created_at);
CREATE INDEX ix_dashboard_reports_reported ON dashboard_reports (reported_id, status);
CREATE INDEX ix_dashboard_reports_reporter_created ON dashboard_reports (reporter_id, created_at);

-- Dashboard settings of a member (a row only once something differs from the defaults).
-- incognito: user-profile-managment does not record this member's profile views (ProfileViewRecorder reads this).
CREATE TABLE dashboard_member_settings (
    user_id     CHAR(36)    NOT NULL,
    incognito   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_dashboard_member_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_dashboard_member_settings_profile FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Daily picks: chosen once per member and UTC day, kept for app.daily-picks.history to avoid repeating members.
CREATE TABLE dashboard_daily_picks (
    member_id   CHAR(36)    NOT NULL,
    pick_date   DATE        NOT NULL,
    position    SMALLINT    NOT NULL,
    picked_id   CHAR(36)    NOT NULL,
    CONSTRAINT pk_dashboard_daily_picks PRIMARY KEY (member_id, pick_date, position),
    CONSTRAINT fk_dashboard_daily_picks_member FOREIGN KEY (member_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_daily_picks_picked FOREIGN KEY (picked_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- "not shown in the last days" check and cascades
CREATE INDEX ix_dashboard_daily_picks_member_picked ON dashboard_daily_picks (member_id, picked_id, pick_date);
CREATE INDEX ix_dashboard_daily_picks_picked ON dashboard_daily_picks (picked_id);
-- nightly cleanup of old picks
CREATE INDEX ix_dashboard_daily_picks_date ON dashboard_daily_picks (pick_date);
