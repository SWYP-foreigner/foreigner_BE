CREATE TABLE hot_keywords
(
    keyword    TEXT PRIMARY KEY, -- 추출된 단어
    frequency  INT,              -- 등장 빈도 (정렬용)
    updated_at TIMESTAMP         -- 마지막 갱신 시간
);