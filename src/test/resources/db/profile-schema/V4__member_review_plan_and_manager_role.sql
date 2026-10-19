-- Muslima Free - admin dashboard support: member review queue, subscription plan, MANAGER role, staff invitations.

-- ---------------------------------------------------------------------------
-- Member review: staff approve or reject a member's name and photos. A member whose review is not APPROVED is
-- shown to other members as "new user". Approved members go back to PENDING_REVIEW when they change their name or
-- photos.
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN review_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW' AFTER deletion_scheduled_at,
    ADD COLUMN review_reason VARCHAR(500) NULL AFTER review_status,
    ADD COLUMN reviewed_by   CHAR(36)     NULL AFTER review_reason,
    ADD COLUMN reviewed_at   DATETIME(6)  NULL AFTER reviewed_by,
    -- subscriptions are owned by the (future) payments service; every account is FREE until then
    ADD COLUMN plan          VARCHAR(20)  NOT NULL DEFAULT 'FREE' AFTER reviewed_at,
    ADD CONSTRAINT fk_users_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_users_review_status CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT ck_users_review_reason CHECK (review_status <> 'REJECTED' OR review_reason IS NOT NULL),
    ADD CONSTRAINT ck_users_plan CHECK (plan IN ('FREE'));

-- Accounts that existed before the review queue are considered approved.
-- Drop this statement to put them in the review queue instead.
UPDATE users SET review_status = 'APPROVED';

-- GET /admin/users?reviewStatus=... (header bell) and ?plan=..., newest first
CREATE INDEX ix_users_review_status_created ON users (review_status, created_at);
CREATE INDEX ix_users_plan_created ON users (plan, created_at);

-- ---------------------------------------------------------------------------
-- Roles: MANAGER sits between MODERATOR and ADMIN
-- ---------------------------------------------------------------------------
ALTER TABLE user_roles DROP CONSTRAINT ck_user_roles_role;
ALTER TABLE user_roles
    ADD CONSTRAINT ck_user_roles_role CHECK (role IN ('MEMBER', 'MODERATOR', 'MANAGER', 'ADMIN'));

-- ---------------------------------------------------------------------------
-- Tokens: staff accounts are created without a password and receive an invitation link
-- ---------------------------------------------------------------------------
ALTER TABLE user_tokens DROP CONSTRAINT ck_tokens_type;
ALTER TABLE user_tokens
    ADD CONSTRAINT ck_tokens_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_CHANGE',
                                                  'STAFF_INVITATION'));
