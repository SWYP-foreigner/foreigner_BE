DROP INDEX IF EXISTS uk35bojfpxnaxph2c9htc9oqkh6;

CREATE UNIQUE INDEX uk35bojfpxnaxph2c9htc9oqkh6
    ON chat_participant (user_id, chatroom_id);