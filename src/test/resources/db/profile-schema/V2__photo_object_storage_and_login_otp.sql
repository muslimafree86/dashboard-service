-- Muslima Free - photo object storage (S3/CloudFront) and one-time-password sign-in.
-- Photo binaries never live in the database: profile_photos keeps only the object key and metadata.

-- ---------------------------------------------------------------------------
-- Deferred deletion of photo objects (no orphans in the bucket, no dangling rows)
--
-- A photo row and its object cannot be removed atomically: the database and S3 are separate systems.
-- The key is therefore recorded here inside the same transaction that removes (or re-points) the row.
-- The object is deleted right after commit; whatever that attempt could not remove - S3 outage, process
-- killed - is retried by a scheduled job until it succeeds. Rows are only ever removed once the object
-- is gone, so the bucket cannot keep objects the database no longer references.
-- ---------------------------------------------------------------------------
CREATE TABLE photo_storage_deletions (
    id               CHAR(36)     NOT NULL,
    storage_key      VARCHAR(255) NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(500) NULL,
    last_attempt_at  DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_photo_storage_deletions PRIMARY KEY (id),
    CONSTRAINT ck_photo_deletions_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- the drain job takes the oldest pending keys first
CREATE INDEX ix_photo_deletions_created ON photo_storage_deletions (created_at);

-- ---------------------------------------------------------------------------
-- Sign-in one-time passwords
--
-- The code itself is never stored: only a bcrypt hash (a 6-digit code has too little entropy for a fast
-- hash to protect it in a database leak). The row carries the pending sign-in's parameters so that no
-- session, access token or refresh token exists until the code is confirmed.
-- ---------------------------------------------------------------------------
CREATE TABLE login_otp_challenges (
    id                CHAR(36)     NOT NULL,
    user_id           CHAR(36)     NOT NULL,
    code_hash         VARCHAR(100) NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    -- masked destination echoed to the client ("h***@example.com"); never the full address
    destination_hint  VARCHAR(254) NOT NULL,
    sign_in_method    VARCHAR(20)  NOT NULL,
    remember_me       BOOLEAN      NOT NULL DEFAULT FALSE,
    device_name       VARCHAR(100) NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    resend_count      INT          NOT NULL DEFAULT 0,
    last_sent_at      DATETIME(6)  NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    consumed_at       DATETIME(6)  NULL,
    ip_address        VARCHAR(45)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_login_otp_challenges PRIMARY KEY (id),
    CONSTRAINT fk_login_otp_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_login_otp_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_login_otp_method CHECK (sign_in_method IN ('PASSWORD', 'GOOGLE')),
    CONSTRAINT ck_login_otp_counters CHECK (attempts >= 0 AND resend_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- invalidating a member's outstanding challenges when a new sign-in starts
CREATE INDEX ix_login_otp_user ON login_otp_challenges (user_id, created_at);
-- nightly removal of expired challenges
CREATE INDEX ix_login_otp_expires ON login_otp_challenges (expires_at);
