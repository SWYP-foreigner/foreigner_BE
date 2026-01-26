-- 1. 충돌 방지를 위해 이동 대상인 QUIZ(8)와 VOTE(9)를 임시 값(음수)으로 변경합니다.
-- 이렇게 하면 8번과 9번 자리가 비게 되어 기존 데이터가 이동할 공간이 생깁니다.
UPDATE board
SET board_id = -8
WHERE board_id = 8;

UPDATE board
SET board_id = -9
WHERE board_id = 9;

-- 2. 기존 2번부터 7번까지의 게시판을 2칸씩 아래로 내립니다.
-- 2번 -> 4번, ... , 7번 -> 9번으로 변경됩니다.
UPDATE board
SET board_id = board_id + 2
WHERE board_id >= 2
  AND board_id <= 7;

-- 3. 임시로 대피시켜 두었던 QUIZ와 VOTE를 목표한 위치인 2번과 3번으로 변경합니다.
UPDATE board
SET board_id = 2
WHERE board_id = -8;

UPDATE board
SET board_id = 3
WHERE board_id = -9;