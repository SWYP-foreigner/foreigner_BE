CREATE TABLE chat_message_translation (
                                          translation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          message_id BIGINT NOT NULL,
                                          language_code VARCHAR(10) NOT NULL,
                                          content TEXT NOT NULL,
                                          created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),


                                          CONSTRAINT uk_message_lang UNIQUE (message_id, language_code)
);

CREATE INDEX idx_message_id ON chat_message_translation (message_id);