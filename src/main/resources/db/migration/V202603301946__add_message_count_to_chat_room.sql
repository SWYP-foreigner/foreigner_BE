-- 1. 컬럼 추가
ALTER TABLE chat_room ADD COLUMN message_count BIGINT NOT NULL DEFAULT 0;

-- 2. 기존 메시지 개수 카운트해서 업데이트
UPDATE chat_room cr
SET message_count = sub.cnt
FROM (
    SELECT chatroom_id, COUNT(*) as cnt
    FROM chat_message
    GROUP BY chatroom_id
) sub
WHERE cr.chatroom_id = sub.chatroom_id;

-- 3. 고속 정렬을 위한 인덱스 추가
CREATE INDEX idx_chatroom_msg_count ON chat_room (message_count DESC);