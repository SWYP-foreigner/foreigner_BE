package core.domain.user.dto;

import core.domain.user.entity.User;

import java.util.List;


import core.domain.user.entity.User;
import java.util.List;

public record UserProfileCardResponse(
        Long userId,
        String firstname,
        String lastname,
        String gender,
        String birthday,
        String country,
        String introduction,
        String purpose,
        List<String> language,
        List<String> hobby,
        String imageKey,
        String followStatus
) {

    public UserProfileCardResponse(User u, List<String> languages, List<String> hobbies, String imageKey, String followStatus) {
        this(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getSex(),
                u.getBirthdate(),
                u.getCountry(),
                u.getIntroduction(),
                u.getPurpose(),
                languages,
                hobbies,
                imageKey,
                followStatus // 👈 추가
        );
    }
}