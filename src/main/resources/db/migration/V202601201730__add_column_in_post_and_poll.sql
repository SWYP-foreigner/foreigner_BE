-- 1. 새로운 컬럼 추가 (이미 있다면 무시하도록 설정)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='poll' AND column_name='title') THEN
ALTER TABLE poll ADD COLUMN title VARCHAR(255);
END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='poll' AND column_name='description') THEN
ALTER TABLE poll ADD COLUMN description TEXT;
END IF;
END $$;

-- 2. 엮여있는 모든 제약 조건을 강제로 끊어버리기 (CASCADE)
-- poll_pkey를 참조하던 poll_option, vote_record의 FK가 같이 날아갑니다.
ALTER TABLE poll DROP CONSTRAINT IF EXISTS poll_pkey CASCADE;

-- 3. 기존 id 컬럼명을 post_id로 변경 (이미 바뀌었다면 에러 무시)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='poll' AND column_name='id') THEN
ALTER TABLE poll RENAME COLUMN id TO post_id;
END IF;
END $$;

-- 4. post_id의 자동 증가(SERIAL) 속성 제거
-- @MapsId는 Post의 ID를 가져다 써야 하므로 자기 혼자 번호를 생성하면 안 됩니다.
ALTER TABLE poll ALTER COLUMN post_id DROP DEFAULT;

-- 5. post_id를 새로운 PRIMARY KEY로 지정
ALTER TABLE poll ADD PRIMARY KEY (post_id);

-- 6. 끊어졌던 관계들 다시 연결하기
-- post 테이블과의 관계
ALTER TABLE poll DROP CONSTRAINT IF EXISTS fk_poll_post;
ALTER TABLE poll ADD CONSTRAINT fk_poll_post
    FOREIGN KEY (post_id) REFERENCES post (post_id) ON DELETE CASCADE;

-- poll_option 테이블과의 관계 (끊어졌던 거 복구)
ALTER TABLE poll_option DROP CONSTRAINT IF EXISTS fk_option_poll;
ALTER TABLE poll_option ADD CONSTRAINT fk_option_poll
    FOREIGN KEY (poll_id) REFERENCES poll (post_id) ON DELETE CASCADE;

-- vote_record 테이블과의 관계 (끊어졌던 거 복구)
ALTER TABLE vote_record DROP CONSTRAINT IF EXISTS fk_vote_poll;
ALTER TABLE vote_record ADD CONSTRAINT fk_vote_poll
    FOREIGN KEY (poll_id) REFERENCES poll (post_id) ON DELETE CASCADE;
DO
NOTICE:  drop cascades to 2 other objects
상세정보:  drop cascades to constraint fk_option_poll on table poll_option
drop cascades to constraint fk_vote_poll on table vote_record
ALTER TABLE
    DO
ALTER TABLE
    ALTER TABLE
ALTER TABLE
    ALTER TABLE
    NOTICE:  constraint "fk_option_poll" of relation "poll_option" does not exist, skipping
ALTER TABLE
    ALTER TABLE
    NOTICE:  constraint "fk_vote_poll" of relation "vote_record" does not exist, skipping
ALTER TABLE
    ALTER TABLE
