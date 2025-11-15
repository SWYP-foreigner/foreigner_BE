ALTER TABLE notification
DROP CONSTRAINT IF EXISTS notification_notification_type_check;

ALTER TABLE notification
    ADD CONSTRAINT notification_notification_type_check
        CHECK (
            notification_type IN (
                                  'post',
                                  'comment',
                                  'chat',
                                  'follow',
                                  'receive',
                                  'newuser',
                                  'followuserpost'
                )
            );