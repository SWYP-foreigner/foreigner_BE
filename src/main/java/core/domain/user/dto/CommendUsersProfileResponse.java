package core.domain.user.dto;

import core.domain.user.entity.User;

import java.util.List;

public record CommendUsersProfileResponse(
        Long userId,
        String firstname,
        String lastname,
        String gender,
        Integer birthday,
        String country,
        String introduction,
        String purpose,
        List<String> language,
        List<String> hobby,
        String imageKey,
        String followStatus // 추가: 팔로우 상태 (PENDING, ACCEPTED, null 등)
) {
    public CommendUsersProfileResponse(User u, List<String> languages, List<String> hobbies, String imageKey, String followStatus) {
        this(u.getId(), u.getFirstName(), u.getLastName(), u.getSex(), extractYear(u.getBirthdate()),
                u.getCountry(), u.getIntroduction(), u.getPurpose(), languages, hobbies, imageKey, followStatus);
    }
    private static Integer extractYear(String birthdate) {
        if (birthdate == null || birthdate.length() != 10) {
            return null;
        }

        return Integer.parseInt(birthdate.substring(birthdate.length() - 4));
    }
}
