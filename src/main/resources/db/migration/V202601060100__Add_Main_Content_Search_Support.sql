-- 1. 뉴스 전용 핫 키워드 테이블 생성
CREATE TABLE main_content_hot_keywords (
       keyword    VARCHAR(255) PRIMARY KEY,
       frequency  INTEGER                  DEFAULT 0,
       updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. HTML 태그 및 URL 제거를 위한 함수 생성 (IMMUTABLE 필수)
CREATE OR REPLACE FUNCTION clean_html_for_search(content text)
RETURNS text AS $$
BEGIN
    IF content IS NULL THEN RETURN ''; END IF;
    -- HTML 태그 제거
    content := regexp_replace(content, '<[^>]*>', ' ', 'g');
    -- URL 제거
    content := regexp_replace(content, '(http|https|ftp)://[^\s/$.?#].[^\s]*', ' ', 'g');
    -- 엔티티 및 연속 공백 정리
RETURN trim(regexp_replace(content, '\s+', ' ', 'g'));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 3. 메인 콘텐츠 테이블에 PGroonga 인덱스 생성
-- (id, title, 그리고 함수를 거친 정제된 본문)
CREATE INDEX IF NOT EXISTS idx_main_page_content_pgroonga
    ON main_page_content USING pgroonga (content_id, title, (clean_html_for_search(html_content)));