ALTER TABLE users
DROP COLUMN IF EXISTS name;

ALTER TABLE users
DROP COLUMN is_in_korea;

ALTER TABLE users
    ADD COLUMN residence VARCHAR(50) NULL AFTER last_seen_at;
