package core.domain.user.dto;

import core.global.enums.Sex;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FollowDTO {
    private Long id;
    private String username;
    private  Integer age;
    private String nationality;
    private Sex sex;

}
