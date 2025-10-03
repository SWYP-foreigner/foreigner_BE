package core.domain.usernotificationsetting.dto;

import core.global.enums.NotificationType;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NotificationSettingUpdateRequestDto {
    private NotificationType notificationType;
    private boolean enabled;
}
