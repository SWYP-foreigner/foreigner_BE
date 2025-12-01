-- 1. 설정 테이블 생성
CREATE TABLE app_settings (
                              setting_key VARCHAR(50) NOT NULL PRIMARY KEY, -- 예: FEEDBACK_URL
                              setting_value TEXT NOT NULL,                  -- 예: https://forms.google...
                              description VARCHAR(255),                     -- 관리자용 설명
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. 초기 데이터 적재 (구글 폼 링크)
INSERT INTO app_settings (setting_key, setting_value, description)
VALUES
    ('FEEDBACK_URL', 'https://docs.google.com/forms/d/e/1FAIpQLSd_EXAMPLE_FEEDBACK/viewform', '유저 피드백 구글폼'),
    ('BUG_REPORT_URL', 'https://docs.google.com/forms/d/e/1FAIpQLSd_EXAMPLE_BUG/viewform', '버그 제보 구글폼');