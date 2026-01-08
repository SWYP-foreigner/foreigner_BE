-- 1. 추천 칩용 (BTS, RM 등 고유명사)
CREATE TABLE main_content_recommendation (
                                           keyword    VARCHAR(255) PRIMARY KEY,
                                           frequency  INTEGER DEFAULT 0,
                                           updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);