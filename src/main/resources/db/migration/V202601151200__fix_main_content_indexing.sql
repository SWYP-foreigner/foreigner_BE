-- 1. 기존 인덱스 삭제 (NormalizerAuto가 없는 구버전)
DROP INDEX IF EXISTS idx_main_page_content_pgroonga;

-- 2. title 컬럼 타입 변경 (character varying -> text)
-- varchar의 제약을 없애 'index scan' 에러를 근본적으로 방지합니다.
ALTER TABLE main_content ALTER COLUMN title TYPE text;

-- 3. 최적화된 PGroonga 인덱스 생성
-- 3-1. 자동완성(suggest) 전용: title 단일 인덱스 (text 타입 전용)
CREATE INDEX idx_main_content_suggest_pgroonga
    ON main_content USING pgroonga (title)
    WITH (tokenizer='TokenBigramSplitSymbolAlphaDigit', normalizers='NormalizerAuto');

-- 3-2. 일반 검색용: 멀티 컬럼 및 함수 인덱스
CREATE INDEX idx_main_page_content_pgroonga
    ON main_content USING pgroonga (content_id, title, clean_html_for_search(html_content))
    WITH (tokenizer='TokenBigramSplitSymbolAlphaDigit', normalizers='NormalizerAuto');