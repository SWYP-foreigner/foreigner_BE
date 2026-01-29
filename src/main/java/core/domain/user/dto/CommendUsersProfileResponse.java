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
        List<String> language,
        List<String> hobby,
        String imageKey
) {
    public CommendUsersProfileResponse(User u, List<String> languages, List<String> hobbies, String imageKey) {
        this(u.getId(), u.getFirstName(), u.getLastName(), u.getSex(), extractYear(u.getBirthdate()), u.getCountry(), u.getIntroduction(), languages, hobbies, imageKey);
    }

    private static Integer extractYear(String birthdate) {
        if (birthdate == null || birthdate.length() != 10) {
            return null;
        }

        return Integer.parseInt(birthdate.substring(birthdate.length() - 4));
    }
}
