package core.domain.user.service;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleOtpService {

    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    @Value("${otp.issuer:Kori-Admin}")
    private String issuer;

    @Value("${otp.magic-code-enabled:false}")
    private boolean magicCodeEnabled;

    public GoogleAuthenticatorKey generateSecretKey() {
        return gAuth.createCredentials();
    }

    public String getGoogleAuthenticatorBarCode(String secretKey, String account) {
        return "otpauth://totp/"
                + issuer + ":" + account
                + "?secret=" + secretKey
                + "&issuer=" + issuer;
    }

    public boolean verifyCode(String secretKey, String code) {
        if (magicCodeEnabled && "000000".equals(code)) {
            log.info(">>>> [OTP] 개발용 만능키(000000) 통과. Issuer: {}", issuer);
            return true;
        }
        try {
            return gAuth.authorize(secretKey, Integer.parseInt(code));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
