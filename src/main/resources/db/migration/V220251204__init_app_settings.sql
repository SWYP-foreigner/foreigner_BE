CREATE TABLE app_settings (
                              setting_key VARCHAR(50) NOT NULL PRIMARY KEY,
                              setting_value TEXT NOT NULL,
                              description VARCHAR(255),
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 초기 데이터 적재
INSERT INTO app_settings (setting_key, setting_value, description)
VALUES
    ('FEEDBACK_URL', 'https://docs.google.com/forms/d/e/1FAIpQLSd_EXAMPLE_FEEDBACK/viewform', '유저 피드백 구글폼'),
    ('BUG_REPORT_URL', 'https://docs.google.com/forms/d/e/1FAIpQLSd_EXAMPLE_BUG/viewform', '버그 제보 구글폼');