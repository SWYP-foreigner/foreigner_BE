
package core.domain.user.dto;

import core.domain.user.entity.User;

import java.time.Instant;

public record UserResponseDto(
        Long userId,
        String name,
        String firstName,
        String lastName,
        String sex,
        Integer birthdate,
        String country,
        String introduction,
        String purpose,
        String language,
        String translateLanguage,
        String hobby,
        Instant createdAt,
        Instant updatedAt,
        String provider,
        String socialId,
        String email,
        boolean isNewUser,
        boolean agreedToPushNotification,
        boolean agreedToTerms,
        String ImageUrl
) {
    public static UserResponseDto from(User user,String ImageUrl) {
        return new UserResponseDto(
                user.getId(),
                user.getName(),
                user.getFirstName(),
                user.getLastName(),
                user.getSex(),
                extractYear(user.getBirthdate()),
                user.getCountry(),
                user.getIntroduction(),
                user.getPurpose(),
                user.getLanguage(),
                user.getTranslateLanguage(),
                user.getHobby(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getProvider(),
                user.getSocialId(),
                user.getEmail(),
                user.isNewUser(),
                user.isAgreedToPushNotification(),
                user.isAgreedToTerms(),
                ImageUrl
        );
    }


    private static Integer extractYear(String birthdate) {
        if (birthdate == null || birthdate.length() != 10) {
            return null;
        }

        return Integer.parseInt(birthdate.substring(birthdate.length() - 4));
    }
}