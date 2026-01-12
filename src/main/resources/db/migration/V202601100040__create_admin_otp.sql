CREATE TABLE admin_otp (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_otp_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT uk_admin_otp_user UNIQUE (user_id)
);