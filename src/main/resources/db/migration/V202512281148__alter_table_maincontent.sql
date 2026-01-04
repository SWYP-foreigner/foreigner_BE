-- main_page_content 테이블에서 publisher_id 컬럼을 삭제합니다.
-- CASCADE 옵션을 사용하여 이 컬럼에 걸려있는 Foreign Key 제약조건도 함께 삭제합니다.
ALTER TABLE main_page_content
    DROP COLUMN publisher_id CASCADE;