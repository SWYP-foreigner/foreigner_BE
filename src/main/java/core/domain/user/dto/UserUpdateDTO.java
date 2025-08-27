package core.domain.user.dto;


import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserUpdateDTO {
    private String firstname;
    private String lastname;
    private String gender;
    private String birthday;
    private String country;
    private String introduction;
    private String purpose;
    private String email;
    private List<String> language;
    private List<String> hobby;
    private String imageKey; // S3 이미지 키
}
