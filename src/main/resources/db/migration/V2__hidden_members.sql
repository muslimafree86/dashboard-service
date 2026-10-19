-- "Not interested": a member removes another member from their own discovery (feed, tabs, similar members).
--
-- One-way and silent, unlike a block (user-profile-managment's user_blocks): the hidden member is not told, can still
-- see the viewer, and the viewer can still open their profile or undo. Rows disappear with either profile.
CREATE TABLE dashboard_hidden_members (
    viewer_id   CHAR(36)    NOT NULL,
    hidden_id   CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_dashboard_hidden_members PRIMARY KEY (viewer_id, hidden_id),
    CONSTRAINT fk_dashboard_hidden_viewer FOREIGN KEY (viewer_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_hidden_member FOREIGN KEY (hidden_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_hidden_not_self CHECK (viewer_id <> hidden_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- the discovery query checks (viewer_id, hidden_id) through the primary key; the list is newest first
CREATE INDEX ix_dashboard_hidden_viewer_created ON dashboard_hidden_members (viewer_id, created_at);
-- cascade from the hidden member's side
CREATE INDEX ix_dashboard_hidden_member ON dashboard_hidden_members (hidden_id);
