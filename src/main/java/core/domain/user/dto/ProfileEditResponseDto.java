package core.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import core.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.List;

/**
 *
 * 마이페이지 프로필 수정 시 반환되는 DTO입니다.
 * 만약 VISITOR -> USER로 승격된 경우, accessToken과 refreshToken이 선택적으로 포함됩니다.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL) // ★ null인 필드는 JSON 응답에서 아예 제외
@Schema(description = "사용자 프로필 수정 응답 DTO (승격 시 토큰 포함)")
public class ProfileEditResponseDto {

    @Schema(description = "이름 (first name)", example = "John")
    private String firstname;

    @Schema(description = "성 (last name)", example = "Doe")
    private String lastname;

    @Schema(description = "성별", example = "Male")
    private String gender;

    @Schema(description = "생년월일 (MM/DD/YYYY 형식)", example = "05/12/1988")
    private String birthday;

    @Schema(description = "국가 코드 ", example = "KR")
    private String country;

    @Schema(description = "자기소개 (최대 70자)", example = "열정적인 개발자입니다.")
    private String introduction;

    @Schema(description = "사용 목적", example = "언어 학습")
    private String purpose;

    @Schema(description = "사용 가능한 언어 목록", example = "[\"english(en)\", \"korean(ko)\"]")
    private List<String> language;

    @Schema(description = "취미 목록", example = "[\"reading\", \"traveling\"]")
    private List<String> hobby;

    @Schema(description = "프로필 이미지 키", example = "https_profile.svg")
    private String imageKey;


    @Schema(description = "(승격 시 발급) 새 액세스 토큰")
    private String accessToken;

    @Schema(description = "(승격 시 발급) 새 리프레시 토큰")
    private String refreshToken;

    /**
     * 기본 생성자 (프로필 정보만 초기화)
     * accessToken과 refreshToken은 기본적으로 null입니다.
     */
    public ProfileEditResponseDto(User user, List<String> languages, List<String> hobbies, String imageKey) {
        this.firstname = user.getFirstName();
        this.lastname = user.getLastName();
        this.gender = user.getSex();
        this.birthday = user.getBirthdate();
        this.country = user.getCountry();
        this.introduction = user.getIntroduction();
        this.purpose = user.getPurpose();
        this.language = languages;
        this.hobby = hobbies;
        this.imageKey = imageKey;
    }

    /**
     * (핵심) 승격된 경우에만 이 메서드를 호출하여 토큰을 추가합니다.
     */
    public void setNewTokens(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }
}