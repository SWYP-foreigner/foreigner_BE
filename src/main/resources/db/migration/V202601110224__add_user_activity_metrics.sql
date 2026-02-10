-- 1. 활동 포인트 (친절한 유저 판단 기준)
ALTER TABLE users
    ADD COLUMN activity_point BIGINT DEFAULT 0;

-- 2. 방문 횟수
ALTER TABLE users
    ADD COLUMN visit_count BIGINT DEFAULT 0;

-- 3. 응답률 (소수점 지원)
ALTER TABLE users
    ADD COLUMN reply_rate DOUBLE PRECISION DEFAULT 0.0;