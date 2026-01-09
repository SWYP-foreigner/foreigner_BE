package core.domain.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminLoginStep1Response {
    private boolean requiresOtp;
    private String tempToken;
    private String qrCodeUrl;
}
