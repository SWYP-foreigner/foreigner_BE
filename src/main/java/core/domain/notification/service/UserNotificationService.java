package core.domain.notification.service;

import core.domain.notification.dto.NotificationSettingListResponse;
import core.domain.notification.dto.NotificationSettingResponse;
import core.domain.notification.dto.NotificationSettingStatusResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.enums.ErrorCode;
import core.global.enums.NotificationType;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList; // ArrayList import 추가
import java.util.List;
import java.util.Optional; // Optional import 추가

@Service
@Transactional
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserRepository userRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;

    /**
     * FCM 기기 토큰을 등록하거나 갱신합니다. (람다 제거 버전)
     */
    public void registerDeviceToken(Long userId, String deviceToken) {
        User user = findUserById(userId);

        Optional<UserDeviceToken> optionalToken = userDeviceTokenRepository.findByDeviceToken(deviceToken);

        if (optionalToken.isPresent()) {
            UserDeviceToken existingToken = optionalToken.get();
            existingToken.updateUser(user);
        } else {
            // 토큰이 존재하지 않을 경우, 새로 생성
            UserDeviceToken newToken = new UserDeviceToken(user, deviceToken);
            userDeviceTokenRepository.save(newToken);
        }
    }
    /* 참고: UserDeviceToken 엔티티에 updateUser 메서드 추가 필요
    public void updateUser(User user) {
        this.user = user;
    }
    */

    /**
     * 사용자의 알림 설정 상태를 확인합니다. (이 메서드는 원래 람다를 사용하지 않았습니다)
     */
    @Transactional(readOnly = true)
    public NotificationSettingStatusResponse getNotificationSettingStatus(Long userId) {
        User user = findUserById(userId);
        boolean needsSetup = !userNotificationSettingRepository.existsByUser(user);

        if (needsSetup) {
            return new NotificationSettingStatusResponse("NEEDS_SETUP");
        } else {
            return new NotificationSettingStatusResponse("CONFIGURED");
        }
    }

    /**
     * 신규 또는 기존 사용자의 알림 설정을 초기화합니다. (람다 제거 버전)
     */
    public void initializeNotificationSettings(Long userId, boolean agreed) {
        User user = findUserById(userId);

        if (userNotificationSettingRepository.existsByUser(user)) {
            return;
        }

        user.updateAgreedToPushNotification(agreed);

        // for-each 루프를 사용하여 List 생성
        List<UserNotificationSetting> settings = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            UserNotificationSetting setting = UserNotificationSetting.builder()
                    .user(user)
                    .notificationType(type)
                    .enabled(agreed)
                    .build();
            settings.add(setting);
        }

        userNotificationSettingRepository.saveAll(settings);
    }

    /**
     * 사용자의 실제 OS 푸시 권한 상태를 서버와 동기화합니다. (이 메서드는 원래 람다를 사용하지 않았습니다)
     */
    public void syncPushAgreement(Long userId, boolean osPermissionGranted) {
        User user = findUserById(userId);
        user.updateAgreedToPushNotification(osPermissionGranted);
    }

    /**
     * 사용자의 현재 모든 카테고리별 알림 설정을 조회합니다. (람다 제거 버전)
     */
    @Transactional(readOnly = true)
    public NotificationSettingListResponse getNotificationSettings(Long userId) {
        User user = findUserById(userId);
        List<UserNotificationSetting> settings = userNotificationSettingRepository.findAllByUser(user);

        // for-each 루프를 사용하여 DTO 리스트로 변환
        List<NotificationSettingResponse> settingResponses = new ArrayList<>();
        for (UserNotificationSetting setting : settings) {
            NotificationSettingResponse response = new NotificationSettingResponse(
                    setting.getNotificationType(),
                    setting.isEnabled()
            );
            settingResponses.add(response);
        }

        return new NotificationSettingListResponse(settingResponses);
    }

    // --- Helper Method ---
    /**
     * ID로 사용자를 찾습니다. (람다 제거 버전)
     */
    private User findUserById(Long userId) {
        Optional<User> optionalUser = userRepository.findById(userId);
        if (optionalUser.isPresent()) {
            return optionalUser.get();
        } else {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}