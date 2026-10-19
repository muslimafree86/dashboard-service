-- Muslima Free - user account & profile schema (MySQL 8.0.16+ / MariaDB 10.6+)
-- Profile belongs to the account: user_profiles.user_id is both PK and FK (strict 1:1).
-- UUIDs are stored as CHAR(36); all DATETIME values are UTC.

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                      CHAR(36)     NOT NULL,
    email                   VARCHAR(254) NOT NULL,
    password_hash           VARCHAR(100) NULL,
    google_subject          VARCHAR(255) NULL,
    email_verified          BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified_at       DATETIME(6)  NULL,
    status                  VARCHAR(30)  NOT NULL,
    status_reason           VARCHAR(500) NULL,
    status_changed_at       DATETIME(6)  NULL,
    failed_login_attempts   INT          NOT NULL DEFAULT 0,
    locked_until            DATETIME(6)  NULL,
    last_login_at           DATETIME(6)  NULL,
    last_active_at          DATETIME(6)  NULL,
    password_changed_at     DATETIME(6)  NULL,
    language                VARCHAR(5)   NOT NULL DEFAULT 'ar',
    time_zone               VARCHAR(64)  NOT NULL DEFAULT 'Africa/Cairo',
    date_time_locale        VARCHAR(20)  NOT NULL DEFAULT 'ar-EG',
    terms_accepted_at       DATETIME(6)  NULL,
    terms_version           VARCHAR(20)  NULL,
    deletion_requested_at   DATETIME(6)  NULL,
    deletion_scheduled_at   DATETIME(6)  NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_google_subject UNIQUE (google_subject),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DEACTIVATED', 'SUSPENDED', 'BANNED', 'PENDING_DELETION', 'DELETED')),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_users_status_created ON users (status, created_at);
CREATE INDEX ix_users_created ON users (created_at);
CREATE INDEX ix_users_last_active ON users (last_active_at);
CREATE INDEX ix_users_deletion_due ON users (status, deletion_scheduled_at);

CREATE TABLE user_roles (
    user_id CHAR(36)    NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('MEMBER', 'MODERATOR', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_user_roles_role ON user_roles (role);

-- ---------------------------------------------------------------------------
-- Profile (personal data + matchmaking attributes + partner preferences + privacy)
-- ---------------------------------------------------------------------------
CREATE TABLE user_profiles (
    user_id                 CHAR(36)     NOT NULL,
    first_name              VARCHAR(50)  NOT NULL,
    last_name               VARCHAR(50)  NOT NULL,
    gender                  VARCHAR(10)  NULL,
    date_of_birth           DATE         NULL,
    nationality             VARCHAR(2)      NULL,
    country_code            VARCHAR(2)      NULL,
    region                  VARCHAR(100) NULL,
    city                    VARCHAR(100) NULL,
    marital_status          VARCHAR(20)  NULL,
    education               VARCHAR(20)  NULL,
    children                VARCHAR(20)  NULL,
    sect                    VARCHAR(20)  NULL,
    profession              VARCHAR(100) NULL,
    employment_sector       VARCHAR(20)  NULL,
    smoking                 VARCHAR(20)  NULL,
    appearance              VARCHAR(20)  NULL,
    body_shape              VARCHAR(20)  NULL,
    weight_kg               SMALLINT     NULL,
    -- partner preferences ("what they look for"); NULL = any
    pref_age_min            SMALLINT     NULL,
    pref_age_max            SMALLINT     NULL,
    pref_country_code       VARCHAR(2)      NULL,
    pref_region             VARCHAR(100) NULL,
    pref_min_education      VARCHAR(20)  NULL,
    pref_children           VARCHAR(20)  NULL,
    pref_smoking            VARCHAR(20)  NULL,
    pref_sect               VARCHAR(20)  NULL,
    pref_employment_sector  VARCHAR(20)  NULL,
    pref_appearance         VARCHAR(20)  NULL,
    pref_body_shape         VARCHAR(20)  NULL,
    -- privacy / presence / onboarding
    visibility              VARCHAR(10)  NOT NULL DEFAULT 'VISIBLE',
    presence_mode           VARCHAR(10)  NOT NULL DEFAULT 'ONLINE',
    onboarding_step         VARCHAR(20)  NOT NULL,
    completeness            SMALLINT     NOT NULL DEFAULT 0,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_profiles_gender CHECK (gender IN ('MALE', 'FEMALE')),
    CONSTRAINT ck_profiles_basic_info CHECK (
        onboarding_step = 'BASIC_INFO'
        OR (gender IS NOT NULL AND date_of_birth IS NOT NULL AND nationality IS NOT NULL)),
    CONSTRAINT ck_profiles_onboarding CHECK (onboarding_step IN ('BASIC_INFO', 'DETAILS', 'APPEARANCE', 'COMPLETED')),
    CONSTRAINT ck_profiles_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT ck_profiles_presence CHECK (presence_mode IN ('ONLINE', 'BUSY')),
    CONSTRAINT ck_profiles_weight CHECK (weight_kg IS NULL OR weight_kg BETWEEN 30 AND 300),
    CONSTRAINT ck_profiles_pref_age CHECK (
        (pref_age_min IS NULL OR pref_age_min BETWEEN 18 AND 99)
        AND (pref_age_max IS NULL OR pref_age_max BETWEEN 18 AND 99)
        AND (pref_age_min IS NULL OR pref_age_max IS NULL OR pref_age_min <= pref_age_max)),
    CONSTRAINT ck_profiles_completeness CHECK (completeness BETWEEN 0 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- admin filtering and the discovery module (gender / country / age); prefix name search
CREATE INDEX ix_profiles_gender_country_dob ON user_profiles (gender, country_code, date_of_birth);
CREATE INDEX ix_profiles_first_name ON user_profiles (first_name);
CREATE INDEX ix_profiles_last_name ON user_profiles (last_name);

-- ---------------------------------------------------------------------------
-- Photos
-- ---------------------------------------------------------------------------
CREATE TABLE profile_photos (
    id                CHAR(36)     NOT NULL,
    user_id           CHAR(36)     NOT NULL,
    storage_key       VARCHAR(255) NOT NULL,
    content_type      VARCHAR(50)  NOT NULL,
    size_bytes        INT          NOT NULL,
    width             INT          NOT NULL,
    height            INT          NOT NULL,
    -- "one main photo per member" is enforced by PhotoService under a row lock on the member's profile
    is_primary        BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(20)  NOT NULL,
    rejection_reason  VARCHAR(300) NULL,
    reviewed_by       CHAR(36)     NULL,
    reviewed_at       DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_profile_photos PRIMARY KEY (id),
    CONSTRAINT fk_photos_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_photos_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_photos_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_photos_user ON profile_photos (user_id, is_primary, created_at);
CREATE INDEX ix_photos_status_created ON profile_photos (status, created_at);

-- ---------------------------------------------------------------------------
-- Sessions (one row per signed-in device; holds the hashed, rotating refresh token)
-- ---------------------------------------------------------------------------
CREATE TABLE user_sessions (
    id                   CHAR(36)     NOT NULL,
    user_id              CHAR(36)     NOT NULL,
    refresh_token_hash   VARCHAR(64)     NOT NULL,
    previous_token_hash  VARCHAR(64)     NULL,
    remember_me          BOOLEAN      NOT NULL,
    user_agent           VARCHAR(512) NULL,
    device_name          VARCHAR(100) NULL,
    ip_address           VARCHAR(45)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    last_used_at         DATETIME(6)  NOT NULL,
    expires_at           DATETIME(6)  NOT NULL,
    revoked_at           DATETIME(6)  NULL,
    revoke_reason        VARCHAR(40)  NULL,
    CONSTRAINT pk_user_sessions PRIMARY KEY (id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_sessions_refresh_hash UNIQUE (refresh_token_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_sessions_user_active ON user_sessions (user_id, revoked_at, last_used_at);
CREATE INDEX ix_sessions_previous_hash ON user_sessions (previous_token_hash);
CREATE INDEX ix_sessions_expires ON user_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- Single-use tokens: e-mail verification, password reset, e-mail change (SHA-256 hash only)
-- ---------------------------------------------------------------------------
CREATE TABLE user_tokens (
    id           CHAR(36)     NOT NULL,
    user_id      CHAR(36)     NOT NULL,
    type         VARCHAR(30)  NOT NULL,
    token_hash   VARCHAR(64)     NOT NULL,
    payload      VARCHAR(254) NULL,
    expires_at   DATETIME(6)  NOT NULL,
    consumed_at  DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT pk_user_tokens PRIMARY KEY (id),
    CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_tokens_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_CHANGE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_tokens_user_type ON user_tokens (user_id, type, created_at);
CREATE INDEX ix_tokens_expires ON user_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- Member blocks (privacy: hides both members from each other)
-- ---------------------------------------------------------------------------
CREATE TABLE user_blocks (
    blocker_id  CHAR(36)    NOT NULL,
    blocked_id  CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_user_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_blocks_not_self CHECK (blocker_id <> blocked_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_blocks_blocked ON user_blocks (blocked_id);

-- ---------------------------------------------------------------------------
-- Audit trail (never contains passwords or tokens); details = small JSON document
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id          CHAR(36)      NOT NULL,
    user_id     CHAR(36)      NULL,
    actor_id    CHAR(36)      NULL,
    event_type  VARCHAR(50)   NOT NULL,
    ip_address  VARCHAR(45)   NULL,
    user_agent  VARCHAR(512)  NULL,
    details     VARCHAR(2000) NULL,
    created_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_audit_user_created ON audit_events (user_id, created_at);
CREATE INDEX ix_audit_type_created ON audit_events (event_type, created_at);
