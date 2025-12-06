package core.domain.notification.dto;

import core.global.enums.NotificationType;

import java.util.List;

public record NotificationBulkEvent(
        List<Long> recipientIds,
        Long senderId,
        NotificationType type,
        Long roomId,
        String content,
        String roomName
) {}