
CREATE TABLE chat_report
(
    id                 BIGSERIAL PRIMARY KEY,
    reporter_user_id   BIGINT,
    reported_user_id   BIGINT      NOT NULL,
    chatroom_id        BIGINT      NOT NULL,
    message_id         BIGINT      NOT NULL,
    message_content    TEXT,
    reason_category    VARCHAR(255) NOT NULL,
    reason_detail      TEXT,
    status             VARCHAR(50) NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_reporter_user
        FOREIGN KEY (reporter_user_id)
            REFERENCES users (user_id), -- "user" (id) -> users (user_id)
    CONSTRAINT fk_reported_user
        FOREIGN KEY (reported_user_id)
            REFERENCES users (user_id), -- "user" (id) -> users (user_id)
    CONSTRAINT fk_chatroom
        FOREIGN KEY (chatroom_id)
            REFERENCES chat_room (chatroom_id), -- chat_room (id) -> chat_room (chatroom_id)

    -- Unique Constraint
    CONSTRAINT uk_reporter_message
        UNIQUE (reporter_user_id, message_id)
);

-- 인덱스
CREATE INDEX idx_reported_user_id ON chat_report (reported_user_id);
CREATE INDEX idx_chatroom_id ON chat_report (chatroom_id);
CREATE INDEX idx_status ON chat_report (status);