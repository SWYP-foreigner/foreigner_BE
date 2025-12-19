CREATE TABLE ai_personas (
                             ai_persona_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'AI 페르소나 ID',
                             user_id BIGINT NOT NULL COMMENT '사용자 ID',
                             instruction TEXT NOT NULL COMMENT '시스템 프롬프트 (스크립트)',
                             background_info TEXT COMMENT 'AI가 기억해야 할 배경 지식',

                             PRIMARY KEY (ai_persona_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 페르소나 정의';