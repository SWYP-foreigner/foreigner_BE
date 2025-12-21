package core.domain.user.dto;

import core.domain.user.entity.User;

import java.util.List;

public record FollowDTO(
        Long userId,
        String firstname,
        String lastname,
        String gender,
        Integer birthday,
        String country,
        String introduction,
        String purpose,
        String email,
        List<String> language,
        List<String> hobby,
        String imageKey
) {

    public FollowDTO(User u, List<String> languages, List<String> hobbies, String imageKey) {
        this(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getSex(),
                extractYear(u.getBirthdate()),
                u.getCountry(),
                u.getIntroduction(),
                u.getPurpose(),
                u.getEmail(),
                languages,
                hobbies,
                imageKey
        );
    }

    private static Integer extractYear(String birthdate) {
        if (birthdate == null || birthdate.length() != 10) {
            return null;
        }

        return Integer.parseInt(birthdate.substring(birthdate.length() - 4));
    }
}
