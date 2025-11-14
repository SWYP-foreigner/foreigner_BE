-- V1__create_chat_report_table.sql

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

    -- Foreign Key Constraints (User, ChatRoom 테이블이 이미 존재한다고 가정)
    CONSTRAINT fk_reporter_user
        FOREIGN KEY (reporter_user_id)
            REFERENCES "user" (id), -- 'user'는 PostgreSQL에서 예약어일 수 있어 따옴표 처리
    CONSTRAINT fk_reported_user
        FOREIGN KEY (reported_user_id)
            REFERENCES "user" (id),
    CONSTRAINT fk_chatroom
        FOREIGN KEY (chatroom_id)
            REFERENCES chat_room (id),

    -- Unique Constraint (reporter_user_id와 message_id 조합은 유일해야 함)
    CONSTRAINT uk_reporter_message
        UNIQUE (reporter_user_id, message_id)
);

-- 인덱스 추가 (선택적이지만 성능 향상에 도움)
-- 신고 대상 사용자, 채팅방, 상태를 기준으로 조회하는 경우가 많을 것이므로 인덱스를 추가합니다.
CREATE INDEX idx_reported_user_id ON chat_report (reported_user_id);
CREATE INDEX idx_chatroom_id ON chat_report (chatroom_id);
CREATE INDEX idx_status ON chat_report (status);