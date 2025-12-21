ALTER TABLE ai_personas
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;

-- 2. 컬럼 코멘트 추가
COMMENT ON COLUMN ai_personas.is_active IS '활성화 여부';