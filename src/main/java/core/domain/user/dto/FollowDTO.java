package core.domain.user.dto;

import core.global.enums.Sex;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class FollowDTO {
    private Long id;
    private String Firstname;
    private String Lastname;
    private LocalDate birthDate;
    private String nationality;
    private Sex sex;

}
