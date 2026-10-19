-- Muslima Free - growth: indexes behind the list endpoints' sort orders and the batched housekeeping, and the lock
-- table that lets several instances share the scheduled jobs.
--
-- Every list endpoint orders by "<column>, then the primary key" (stable pages). InnoDB appends the primary key to
-- each secondary index, so an index on (filter, <column>) serves that order directly: the database reads the first
-- page from the index instead of sorting every matching row.

-- ---------------------------------------------------------------------------
-- List endpoints
-- ---------------------------------------------------------------------------
-- GET /me/blocks: a member's blocks, newest first (the primary key (blocker_id, blocked_id) cannot give that order)
CREATE INDEX ix_blocks_blocker_created ON user_blocks (blocker_id, created_at);

-- GET /admin/users?sort=lastLoginAt | sort=completeness: without these, every page sorts all members
CREATE INDEX ix_users_last_login ON users (last_login_at);
CREATE INDEX ix_profiles_completeness ON user_profiles (completeness);

-- ---------------------------------------------------------------------------
-- Housekeeping: sessions revoked before the retention period (expired ones already use ix_sessions_expires)
-- ---------------------------------------------------------------------------
CREATE INDEX ix_sessions_revoked ON user_sessions (revoked_at);

-- ---------------------------------------------------------------------------
-- Scheduled-job locks (ShedLock): one row per job; a job runs on the instance that updates its row first.
-- Times are UTC and computed by the database (UTC_TIMESTAMP), never by the instances.
-- ---------------------------------------------------------------------------
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  DATETIME(3)  NOT NULL,
    locked_at   DATETIME(3)  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
