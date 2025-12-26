ALTER TABLE board DROP CONSTRAINT IF EXISTS board_board_category_check;

ALTER TABLE board
ALTER COLUMN board_category TYPE VARCHAR(50)
    USING CASE
        WHEN board_category::integer = 0 THEN 'ALL'
        WHEN board_category::integer = 1 THEN 'NEWS'
        WHEN board_category::integer = 2 THEN 'TIP'
        WHEN board_category::integer = 3 THEN 'QNA'
        WHEN board_category::integer = 4 THEN 'EVENT'
        WHEN board_category::integer = 5 THEN 'FREE_TALK'
        WHEN board_category::integer = 6 THEN 'ACTIVITY'
        ELSE 'FREE_TALK'
END;
