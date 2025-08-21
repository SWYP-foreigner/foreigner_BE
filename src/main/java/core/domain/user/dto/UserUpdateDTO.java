package core.domain.user.dto;


import core.global.enums.Sex;
import jakarta.persistence.Column;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class UserUpdateDTO {
    private String firstname;
    private String lastname;
    private Sex sex;
    private LocalDate birthDate;
    private String nationality;
    private String introduction;
    private String visitPurpose;
    private String languages;
    private String hobby;
    private String profileImageUrl;
}
