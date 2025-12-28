-- main_page_content 테이블에 컬럼 추가
ALTER TABLE main_page_content
    ADD COLUMN type VARCHAR(50),
    ADD COLUMN view_count BIGINT DEFAULT 0;
