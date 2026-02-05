-- 1. 먼저 순서를 바꿀 대상인 QUIZ(8)와 VOTE(9)를 임시 음수 값으로 대피시킵니다.
UPDATE board
SET board_id = -8
WHERE board_id = 8;

UPDATE board
SET board_id = -9
WHERE board_id = 9;

-- 2. 아래로 밀려날 기존 2번~7번 데이터들도 충돌 방지를 위해 일단 음수로 변경합니다.
-- (예: 2 -> -2, 7 -> -7)
UPDATE board
SET board_id = -board_id
WHERE board_id >= 2
  AND board_id <= 7;

-- 3. 음수로 대피했던 기존 데이터(-2 ~ -7)를 양수로 되돌리면서 +2를 더해 위치를 이동시킵니다.
-- 식: (-board_id) + 2
-- 예: -2 -> 2 + 2 = 4
-- 예: -7 -> 7 + 2 = 9
UPDATE board
SET board_id = (-board_id) + 2
WHERE board_id <= -2
  AND board_id >= -7;

-- 4. 마지막으로 대피해 있던 QUIZ(-8)와 VOTE(-9)를 빈자리인 2, 3번으로 이동시킵니다.
UPDATE board
SET board_id = 2
WHERE board_id = -8;

UPDATE board
SET board_id = 3
WHERE board_id = -9;