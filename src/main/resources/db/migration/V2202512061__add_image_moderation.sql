ALTER TABLE image
    ADD COLUMN moderation_status VARCHAR(20) DEFAULT 'CLEAN' NOT NULL;

ALTER TABLE image
    ADD COLUMN moderation_reason VARCHAR(255);