CREATE TABLE chat_message_translation (
                                          translation_id  BIGSERIAL       NOT NULL,
                                          message_id      BIGINT          NOT NULL,
                                          language_code   VARCHAR(10)     NOT NULL,
                                          content         TEXT            NOT NULL,
                                          created_at      TIMESTAMP(6),

                                          PRIMARY KEY (translation_id)
);

ALTER TABLE chat_message_translation
    ADD CONSTRAINT uk_message_lang UNIQUE (message_id, language_code);

CREATE INDEX idx_message_id ON chat_message_translation (message_id);


ALTER TABLE chat_message_translation
    ADD CONSTRAINT fk_chat_message_translation_message_id
        FOREIGN KEY (message_id)
            REFERENCES chat_message (message_id) ON DELETE CASCADE;