CREATE TABLE user_feedbacks (
                                feedback_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                user_id BIGINT NOT NULL,
                                source VARCHAR(20),
                                content TEXT NOT NULL,
                                created_at DATETIME(6),
                                CONSTRAINT fk_user_feedbacks_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
;