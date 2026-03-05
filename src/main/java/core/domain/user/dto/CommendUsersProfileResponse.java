package core.domain.user.dto;

import core.domain.user.entity.User;
import core.domain.user.service.FriendType;

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
        FriendType friendType // FriendType 열거형 사용
) {
    // 생성자 파라미터 타입을 FriendType으로 일치시킵니다.
    public CommendUsersProfileResponse(User u, List<String> languages, List<String> hobbies, String imageKey, FriendType friendType) {
        this(u.getId(), u.getFirstName(), u.getLastName(), u.getSex(), extractYear(u.getBirthdate()),
                u.getCountry(), u.getIntroduction(), u.getPurpose(), languages, hobbies, imageKey, friendType);
    }

    private static Integer extractYear(String birthdate) {
        if (birthdate == null || birthdate.length() < 4) return null;
        try {
            // "YYYY-MM-DD" 형식이면 앞 4자리를, "DD-MM-YYYY" 형식이면 뒤 4자리를 파싱
            return Integer.parseInt(birthdate.substring(birthdate.length() - 4));
        } catch (Exception e) {
            return null;
        }
    }
}