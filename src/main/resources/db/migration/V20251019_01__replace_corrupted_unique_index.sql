-- 반드시 트랜잭션 바깥에서 실행 (Flyway 실행 시 이 마이그레이션만 비트랜잭션 처리)
SET lock_timeout = '5s';
SET statement_timeout = '5min';

-- 1) 새 유니크 인덱스(정상 이름) 생성 - CONCURRENTLY로 DML 차단 최소화
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_chatroom_user_idx
    ON chat_participant (chatroom_id, user_id);

-- 2) 동일 이름의 유니크 제약이 없으면, 방금 만든 인덱스에 제약을 부착
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_chatroom_user') THEN
ALTER TABLE chat_participant
    ADD CONSTRAINT uk_chatroom_user UNIQUE USING INDEX uk_chatroom_user_idx;
END IF;
END$$;

-- 3) 깨진 고유 제약(=백업 인덱스도 동일 이름)을 제거 → 손상 인덱스 정리
ALTER TABLE chat_participant
DROP CONSTRAINT IF EXISTS uk35bojfpxnaxph2c9htc9oqkh6;
