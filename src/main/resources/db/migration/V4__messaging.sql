-- Messaging between matched members (الرسائل: inbox / sent, chat thread). Text only, as in the Figma.
-- A conversation and all its messages are deleted with either member's profile (account purge).

-- One conversation per pair of members; member_a < member_b so the pair has a single spelling.
CREATE TABLE dashboard_conversations (
    id          CHAR(36)    NOT NULL,
    member_a    CHAR(36)    NOT NULL,
    member_b    CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_dashboard_conversations PRIMARY KEY (id),
    CONSTRAINT uk_dashboard_conversations_pair UNIQUE (member_a, member_b),
    CONSTRAINT fk_dashboard_conversations_a FOREIGN KEY (member_a) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_conversations_b FOREIGN KEY (member_b) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_conversations_order CHECK (member_a < member_b)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE INDEX ix_dashboard_conversations_b ON dashboard_conversations (member_b);

-- Each member's side of a conversation: what their inbox shows (last message, unread count) without reading messages.
CREATE TABLE dashboard_conversation_members (
    member_id             CHAR(36)     NOT NULL,
    other_id              CHAR(36)     NOT NULL,
    conversation_id       CHAR(36)     NOT NULL,
    last_message_at       DATETIME(6)  NOT NULL,
    last_message_preview  VARCHAR(120) NOT NULL,
    last_sender_id        CHAR(36)     NOT NULL,
    unread_count          INT          NOT NULL DEFAULT 0,
    last_read_at          DATETIME(6)  NULL,
    CONSTRAINT pk_dashboard_conversation_members PRIMARY KEY (member_id, other_id),
    CONSTRAINT fk_dashboard_conv_members_conversation FOREIGN KEY (conversation_id)
        REFERENCES dashboard_conversations (id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_conv_members_unread CHECK (unread_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- inbox / sent, most recent first
CREATE INDEX ix_dashboard_conv_members_member_last ON dashboard_conversation_members (member_id, last_message_at);
CREATE INDEX ix_dashboard_conv_members_conversation ON dashboard_conversation_members (conversation_id);

-- Messages. Time-ordered (UUIDv7) ids: inserts append at the end of the primary key.
CREATE TABLE dashboard_messages (
    id               CHAR(36)      NOT NULL,
    conversation_id  CHAR(36)      NOT NULL,
    sender_id        CHAR(36)      NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    CONSTRAINT pk_dashboard_messages PRIMARY KEY (id),
    CONSTRAINT fk_dashboard_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES dashboard_conversations (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- a thread, in both directions (latest page, older history, polling for new messages)
CREATE INDEX ix_dashboard_messages_conversation_created ON dashboard_messages (conversation_id, created_at, id);
-- the per-minute send limit
CREATE INDEX ix_dashboard_messages_sender_created ON dashboard_messages (sender_id, created_at);
