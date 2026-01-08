-- 1. 테이블 이름 변경
ALTER TABLE main_page_content RENAME TO main_content;


ALTER TABLE main_content
    RENAME CONSTRAINT main_page_content_pkey TO main_content_pkey;