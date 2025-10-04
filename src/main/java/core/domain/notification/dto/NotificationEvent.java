package core.domain.notification.dto;

import core.domain.user.entity.User;
import core.global.enums.NotificationType;

public record NotificationEvent(
        User recipient,
        User actor,
        NotificationType notificationType,
        Long referenceId,
        String contentSnippet
) {}