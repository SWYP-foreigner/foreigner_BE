-- 1. Poll 테이블의 자동 증가 속성 제거 (이미 했다면 생략 가능)
ALTER TABLE poll ALTER COLUMN post_id DROP DEFAULT;

-- 2. PollOption 테이블의 정답 여부 컬럼 null 허용 (투표 기능을 위해 필수)
ALTER TABLE poll_option ALTER COLUMN is_correct DROP NOT NULL;

-- 3. PollOption의 투표 수 기본값 보장
ALTER TABLE poll_option ALTER COLUMN vote_count SET DEFAULT 0;