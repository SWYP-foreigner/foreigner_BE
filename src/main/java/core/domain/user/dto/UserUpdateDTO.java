package core.domain.user.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
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
    private String imageKey;
    private Long userId;
}
