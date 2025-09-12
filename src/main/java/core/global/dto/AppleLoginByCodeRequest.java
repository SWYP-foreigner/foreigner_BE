package core.global.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 단순 코드로만 로그인 하는 방식
 */
@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AppleLoginByCodeRequest {
    private String authorizationCode;
    private String codeVerifier;  // PKCE 미사용 시 null/공란
    private String redirectUri;   // iOS 네이티브는 보통 빈 값(필요 시만)
}
