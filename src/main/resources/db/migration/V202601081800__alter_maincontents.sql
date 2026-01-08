-- 1. 테이블 이름 변경
ALTER TABLE main_page_content RENAME TO main_content;

-- 2. 외래 키(FK) 제약 조건 이름 변경
-- 일관성을 위해 제약 조건명도 함께 변경하는 것이 유지보수에 좋습니다.
ALTER TABLE main_content
    RENAME CONSTRAINT FK_main_page_content_publisher TO FK_main_content_publisher;