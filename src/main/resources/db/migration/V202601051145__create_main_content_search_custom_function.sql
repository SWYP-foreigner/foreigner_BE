CREATE OR REPLACE FUNCTION clean_html_for_search(content text)
RETURNS text AS $$
BEGIN
    IF content IS NULL THEN RETURN ''; END IF;
    -- 1. HTML 태그 제거
    content := regexp_replace(content, '<[^>]*>', ' ', 'g');
    -- 2. URL 제거
    content := regexp_replace(content, '(http|https|ftp)://[^\s/$.?#].[^\s]*', ' ', 'g');
RETURN trim(regexp_replace(content, '\s+', ' ', 'g'));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 기존 인덱스가 있다면 삭제 후 생성
CREATE INDEX idx_main_page_content_pgroonga ON main_page_content
    USING pgroonga (content_id, title, (clean_html_for_search(html_content)));