CREATE TABLE user_feedbacks (
                                feedback_id BIGSERIAL PRIMARY KEY,       -- AUTO_INCREMENT -> BIGSERIAL
                                user_id BIGINT NOT NULL,
                                source VARCHAR(20),
                                content TEXT NOT NULL,
                                created_at TIMESTAMP,                    -- DATETIME(6) -> TIMESTAMP

                                CONSTRAINT fk_user_feedbacks_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);