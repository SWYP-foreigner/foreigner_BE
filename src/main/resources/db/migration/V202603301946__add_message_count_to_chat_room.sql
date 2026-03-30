-- 1. 컬럼 추가
ALTER TABLE chat_room ADD COLUMN message_count BIGINT NOT NULL DEFAULT 0;

-- 2. 기존 메시지 개수 카운트해서 업데이트 (시간이 조금 걸릴 수 있습니다)
UPDATE chat_room cr
SET message_count = (
    SELECT COUNT(*) FROM chat_message cm WHERE cm.chatroom_id = cr.chatroom_id
);

-- 3. 고속 정렬을 위한 인덱스 추가
CREATE INDEX idx_chatroom_msg_count ON chat_room (message_count DESC);