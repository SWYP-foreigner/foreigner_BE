-- 1. Poll 테이블 생성
CREATE TABLE poll (
                      id BIGSERIAL PRIMARY KEY,
                      title VARCHAR(255) NOT NULL,
                      type VARCHAR(20) NOT NULL, -- VOTE, QUIZ
                      close_at TIMESTAMP WITH TIME ZONE,
                      user_id BIGINT,
                      total_vote_count BIGINT DEFAULT 0,
                      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_poll_user FOREIGN KEY (user_id) REFERENCES users(user_id) -- id -> user_id로 수정
);

-- 2. PollOption 테이블 생성
CREATE TABLE poll_option (
                             id BIGSERIAL PRIMARY KEY,
                             poll_id BIGINT NOT NULL,
                             content VARCHAR(255) NOT NULL,
                             is_correct BOOLEAN DEFAULT FALSE,
                             vote_count BIGINT DEFAULT 0,
                             CONSTRAINT fk_option_poll FOREIGN KEY (poll_id) REFERENCES poll(id) ON DELETE CASCADE
);

-- 3. VoteRecord 테이블 생성
CREATE TABLE vote_record (
                             id BIGSERIAL PRIMARY KEY,
                             user_id BIGINT NOT NULL,
                             poll_id BIGINT NOT NULL,
                             poll_option_id BIGINT NOT NULL,
                             voted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                             CONSTRAINT fk_vote_user FOREIGN KEY (user_id) REFERENCES users(user_id), -- id -> user_id로 수정
                             CONSTRAINT fk_vote_poll FOREIGN KEY (poll_id) REFERENCES poll(id),
                             CONSTRAINT fk_vote_option FOREIGN KEY (poll_option_id) REFERENCES poll_option(id),
                             CONSTRAINT uk_user_poll UNIQUE (user_id, poll_id)
);