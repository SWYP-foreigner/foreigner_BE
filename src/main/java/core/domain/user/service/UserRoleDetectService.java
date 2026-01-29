package core.domain.user.service;

import core.domain.user.entity.User;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.UserErrorCode;
import core.global.entity.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class UserRoleDetectService {
    private static final String DEFAULT_PROFILE_URL = "https://cdn.ko-ri.cloud/default/character_05.svg";
    private final ImageService imageService;

    /* TODO: 클라이언트에서 기본 프로필 사진인 경우 변경을 강제하는 로직 추가 예정
     *  아래 조건들이 추후 검증 로직에 포함되어야 함:
     *   - DEFAULT_PROFILE_URL.equals(userProfileKey)
     *  (즉, 기본 이미지이거나 프로필이 설정되지 않은 경우, 변경하도록 강제)
     */

    public void isProfileSetUpUser(User user) {
        String userProfileKey = imageService.getUserProfileKey(user.getId());
        if (user.getBirthdate() == null
            || user.getLastName() == null
            || user.getFirstName() == null
            || user.getIntroduction() == null
            || user.getLanguage() == null
            || user.getHobby() == null
            || user.getSex() == null
            || userProfileKey == null) {
            throw new BusinessException(UserErrorCode.PROFILE_SET_NOT_COMPLETED);
        }
    }
}
