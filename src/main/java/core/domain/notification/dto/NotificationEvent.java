package core.domain.notification.dto;

import core.domain.user.entity.User;
import core.global.enums.NotificationType;

public record NotificationEvent(
        Long recipientUserId,
        NotificationType type,
        String message,
        Long referenceId,
        User sender
) {}