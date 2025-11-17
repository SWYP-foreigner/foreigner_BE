-- 1) 기존 체크 제약조건 제거
ALTER TABLE image DROP CONSTRAINT image_image_type_check;

-- 2) smallint -> varchar 로 타입 변경 + 값 매핑
ALTER TABLE image
ALTER COLUMN image_type TYPE varchar(20)
USING (
    CASE image_type
        WHEN 0 THEN 'POST'
        WHEN 1 THEN 'USER'
        WHEN 2 THEN 'CHAT_ROOM'
    END
);

-- 3) (선택) 새 체크 제약조건 추가
ALTER TABLE image
    ADD CONSTRAINT image_image_type_check
        CHECK (image_type IN ('POST', 'USER', 'CHAT_ROOM'));
