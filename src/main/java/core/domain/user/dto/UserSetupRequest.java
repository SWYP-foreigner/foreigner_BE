package core.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import core.domain.user.entity.User;
import core.global.config.EmptyToNullStringDeserializer;
import core.global.exception.ValidBirthday;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "사용자 프로필 설정 요청 DTO")
public record UserSetupRequest(

        @Schema(description = "이름 (first name)", example = "John")
        String firstname,

        @Schema(description = "성 (last name)", example = "Doe")
        String lastname,

        @Schema(description = "성별", example = "Male")
        @JsonDeserialize(using = EmptyToNullStringDeserializer.class)
        @Pattern(
                regexp = "^(Male|Female|NoGender)$",
                message = "gender는 Male|Female|NoGender 중 하나여야 합니다."
        )
        String gender,

        @Schema(description = "생년월일 (MM/DD/YYYY 형식)", example = "05/12/1988")
        @ValidBirthday
        String birthday,

        @Schema(description = "국가 코드", example = "KR")
        String country,

        @Schema(description = "자기소개 (최대 70자)", example = "열정적인 개발자입니다.")
        String introduction,

        @Schema(description = "사용 목적", example = "언어 학습")
        String purpose,

        @Schema(description = "사용자 이메일", example = "john.doe@example.com")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @Schema(description = "사용 가능한 언어 목록", example = "[\"english(en)\", \"korean(ko)\"]")
        List<String> language,

        @Schema(description = "취미 목록", example = "[\"reading\", \"traveling\"]")
        List<String> hobby,

        @Schema(description = "프로필 이미지 키", example = "https://kr.object.ncloudstorage.com/foreigner-bucket/default/character_02.svg")
        @Size(max = 255)
        String imageKey

) {
    public UserSetupRequest(User user, List<String> languages, List<String> hobbies, String imageKey) {
        this(
                user.getFirstName(),
                user.getLastName(),
                user.getSex(),
                user.getBirthdate(),
                user.getCountry(),
                user.getIntroduction(),
                user.getPurpose(),
                user.getEmail(),
                languages,
                hobbies,
                imageKey
        );
    }
}
