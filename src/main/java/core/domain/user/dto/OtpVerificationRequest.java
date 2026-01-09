package core.domain.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OtpVerificationRequest {
    private String email;
    private String otpCode;
    private String tempToken;
}
