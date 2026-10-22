-- dashboard_reports becomes the platform's only member-report store. Members file reports here
-- (POST /api/v1/dashboard/reports/{memberId}); admin-managment-service's moderation screens read and close the same
-- rows (its entity UserReport maps this table) instead of keeping their own admin_user_reports table, which its
-- migration V1008 copies here and drops. Adds what the admin screens record: the reported message, the account action
-- taken, and an update time and version for its optimistic locking. Reasons are the union of both former lists.

ALTER TABLE dashboard_reports
    ADD COLUMN message_id   CHAR(36)    NULL AFTER context,
    ADD COLUMN action_taken VARCHAR(20) NULL AFTER status,
    ADD COLUMN updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at,
    ADD COLUMN version      BIGINT      NOT NULL DEFAULT 0;

UPDATE dashboard_reports SET updated_at = COALESCE(reviewed_at, created_at);

ALTER TABLE dashboard_reports DROP CONSTRAINT ck_dashboard_reports_reason;
ALTER TABLE dashboard_reports
    ADD CONSTRAINT ck_dashboard_reports_reason CHECK (reason IN ('FAKE_PROFILE', 'INAPPROPRIATE_PHOTOS', 'HARASSMENT',
        'SCAM', 'OFFENSIVE_MESSAGES', 'NOT_SERIOUS', 'UNDERAGE', 'SPAM', 'INAPPROPRIATE_CONTENT', 'OTHER')),
    ADD CONSTRAINT ck_dashboard_reports_action CHECK (action_taken IS NULL OR action_taken IN ('NONE', 'SUSPEND', 'BAN'));

-- admin "most reported members": WHERE status = 'OPEN' GROUP BY reported_id, and the bulk close of a suspended or
-- banned member's open reports
CREATE INDEX ix_dashboard_reports_status_reported ON dashboard_reports (status, reported_id);
