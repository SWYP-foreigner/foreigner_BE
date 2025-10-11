-- 1) 컬럼 추가: NOT NULL + DEFAULT 'USER'  (리라이트 없이 빠르게)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS user_role varchar(20) NOT NULL DEFAULT 'USER';

-- 2) 체크 제약조건 (NOT VALID → 검증 분리로 락 최소화)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'users_user_role_check'
  ) THEN
ALTER TABLE users
    ADD CONSTRAINT users_user_role_check
        CHECK (user_role IN ('ADMIN','USER','VISITOR')) NOT VALID;
END IF;
END$$;

ALTER TABLE users
    VALIDATE CONSTRAINT users_user_role_check;

-- 3) 기존 데이터 백필:
--    birthdate, purpose, introduction, language, hobby, sex 중 하나라도 NULL이면 VISITOR
--    단, ADMIN은 유지
UPDATE users
SET user_role = 'VISITOR'
WHERE user_role <> 'ADMIN'
  AND (
    birthdate    IS NULL OR
    purpose      IS NULL OR
    introduction IS NULL OR
        language     IS NULL OR
        hobby        IS NULL OR
        sex          IS NULL
    );

-- 4) 유지용 트리거: INSERT/UPDATE 시 자동으로 VISITOR/USER 설정 (ADMIN은 건드리지 않음)
--    필요 없다면 이 블록은 생략 가능

-- 함수 생성
CREATE OR REPLACE FUNCTION users_set_role_by_profile()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- ADMIN은 그대로 둔다
  IF NEW.user_role = 'ADMIN' THEN
    RETURN NEW;
END IF;

  -- 프로필 미완성 → VISITOR, 모두 채워짐 → USER
  IF NEW.birthdate IS NULL
     OR NEW.purpose IS NULL
     OR NEW.introduction IS NULL
     OR NEW.language IS NULL
     OR NEW.hobby IS NULL
     OR NEW.sex IS NULL THEN
    NEW.user_role := 'VISITOR';
ELSE
    NEW.user_role := 'USER';
END IF;

RETURN NEW;
END;
$$;

-- 트리거 생성 (중복 방지)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_trigger WHERE tgname = 'trg_users_set_role_by_profile'
  ) THEN
CREATE TRIGGER trg_users_set_role_by_profile
    BEFORE INSERT OR UPDATE OF birthdate, purpose, introduction, language, hobby, sex, user_role
                     ON users
                         FOR EACH ROW
                         EXECUTE FUNCTION users_set_role_by_profile();
END IF;
END$$;
