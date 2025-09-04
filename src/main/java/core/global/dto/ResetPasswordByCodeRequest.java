package core.global.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordByCodeRequest {
    private String email;
    private String code;
    private String newPassword;
}
