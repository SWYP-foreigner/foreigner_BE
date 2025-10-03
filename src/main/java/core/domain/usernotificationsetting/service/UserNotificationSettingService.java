package core.domain.usernotificationsetting.service;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.usernotificationsetting.dto.NotificationSettingInitItem;
import core.domain.usernotificationsetting.dto.NotificationSettingInitRequestDto;
import core.domain.usernotificationsetting.dto.NotificationSettingResponseDto;
import core.domain.usernotificationsetting.dto.NotificationSettingUpdateRequestDto;
import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserNotificationSettingService {

    private final UserNotificationSettingRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<NotificationSettingResponseDto> getUserNotificationSettings(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_UNAUTHORIZED));

        List<UserNotificationSetting> settings = repository.findByUserId(user.getId());
        if (settings.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_SETTING_NOT_FOUND);
        }

        return settings.stream()
                .map(s -> new NotificationSettingResponseDto(s.getNotificationType(), s.isEnabled()))
                .toList();
    }

    @Transactional
    public NotificationSettingResponseDto updateUserNotificationSetting(Long userId, NotificationSettingUpdateRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_UNAUTHORIZED));

        UserNotificationSetting setting = repository.findByUserIdAndNotificationType(user.getId(), request.getNotificationType())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_SETTING_NOT_FOUND));

        setting.updateEnabled(request.isEnabled());

        return new NotificationSettingResponseDto(setting.getNotificationType(), setting.isEnabled());
    }

    @Transactional
    public void initializeNotificationSettings(Long userId, NotificationSettingInitRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        for (NotificationSettingInitItem item : request.getSettings()) {
            UserNotificationSetting setting = repository
                    .findByUserIdAndNotificationType(userId, item.getNotificationType())
                    .orElse(new UserNotificationSetting(user, item.getNotificationType(), item.isEnabled()));

            setting.updateEnabled(item.isEnabled());
            repository.save(setting);
        }
    }
}
