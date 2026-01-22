-- 1. ai_type 컬럼 추가
ALTER TABLE ai_personas ADD COLUMN ai_type VARCHAR(20);

-- 2. 기존 데이터 마이그레이션
UPDATE ai_personas SET ai_type = 'ALL' WHERE ai_type IS NULL;

-- 3. NOT NULL 제약조건 추가
ALTER TABLE ai_personas ALTER COLUMN ai_type SET NOT NULL;