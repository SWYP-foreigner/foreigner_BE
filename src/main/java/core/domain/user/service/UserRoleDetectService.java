package core.domain.user.service;

import core.domain.user.entity.User;
import core.global.exception.BusinessException;
import core.global.exception.UserErrorCode;
import core.global.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class UserRoleDetectService {
    private static final String DEFAULT_PROFILE_URL = "https://cdn.ko-ri.cloud/default/character_05.svg";
    private final ImageService imageService;

    public void isProfileSetUpUser(User user) {
        String userProfileKey = imageService.getUserProfileKey(user.getId());
        if (user.getBirthdate() == null
            || user.getLastName() == null
            || user.getFirstName() == null
            || user.getPurpose() == null
            || user.getIntroduction() == null
            || user.getLanguage() == null
            || user.getHobby() == null
            || user.getSex() == null
            || userProfileKey == null
            || DEFAULT_PROFILE_URL.equals(userProfileKey)) {
            throw new BusinessException(UserErrorCode.PROFILE_SET_NOT_COMPLETED);
        }
    }
}
