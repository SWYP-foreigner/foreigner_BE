-- V...__create_chat_report_table.sql (수정본)

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

    -- Foreign Key Constraints 수정
    CONSTRAINT fk_reporter_user
        FOREIGN KEY (reporter_user_id)
            REFERENCES users (user_id), -- "user" (id) -> users (user_id)로 변경
    CONSTRAINT fk_reported_user
        FOREIGN KEY (reported_user_id)
            REFERENCES users (user_id), -- "user" (id) -> users (user_id)로 변경
    CONSTRAINT fk_chatroom
        FOREIGN KEY (chatroom_id)
            REFERENCES chat_room (id), -- chat_room 테이블은 그대로 둔다고 가정합니다.

    -- Unique Constraint
    CONSTRAINT uk_reporter_message
        UNIQUE (reporter_user_id, message_id)
);

-- 인덱스
CREATE INDEX idx_reported_user_id ON chat_report (reported_user_id);
CREATE INDEX idx_chatroom_id ON chat_report (chatroom_id);
CREATE INDEX idx_status ON chat_report (status);