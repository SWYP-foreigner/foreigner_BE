package core.domain.user.dto;


import core.global.enums.Sex;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserUpdateDTO {
    private String name;
    private Sex sex;
    private Integer age;
    private String nationality;
    private String introduction;
    private String visitPurpose;
    private String languages;
    private String hobby;
}
