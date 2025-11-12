ALTER TABLE users
    ADD COLUMN IF NOT EXISTS user_role varchar(20) NOT NULL DEFAULT 'USER';


DO $$
BEGIN
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_user_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_user_role_check
        CHECK (user_role IN ('ADMIN','USER','VISITOR', 'AI', 'OUTCAST')) NOT VALID;
END
$$;

ALTER TABLE users
    VALIDATE CONSTRAINT users_user_role_check;


UPDATE users
SET user_role = 'VISITOR'
WHERE user_role <> 'ADMIN'
  AND (
    birth_date    IS NULL OR
    visit_purpose IS NULL OR
    introduction  IS NULL OR
    languages     IS NULL OR
    hobby         IS NULL OR
    sex           IS NULL OR
    nationality   IS NULL
    );