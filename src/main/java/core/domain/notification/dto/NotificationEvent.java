package core.domain.notification.dto;

import core.domain.user.entity.User;
import core.global.enums.NotificationType;

public record NotificationEvent(
        Long recipientId,
        Long actorId,
        NotificationType notificationType,
        Long referenceId,
        String contentSnippet
) {}