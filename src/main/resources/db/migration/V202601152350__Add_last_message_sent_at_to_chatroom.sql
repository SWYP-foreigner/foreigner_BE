ALTER TABLE chat_room
    ADD COLUMN last_message_sent_at TIMESTAMP;

UPDATE chat_room cr
SET last_message_sent_at = (
    SELECT MAX(cm.sent_at)
    FROM chat_message cm
    WHERE cm.chatroom_id = cr.chatroom_id
);

UPDATE chat_room
SET last_message_sent_at = created_at
WHERE last_message_sent_at IS NULL;

CREATE INDEX idx_chatroom_last_msg_at ON chat_room (last_message_sent_at);