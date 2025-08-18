package core.global.dto;


import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Getter
@Builder
public class AppleTokenRequest {
    private String grantType;      // 항상 "authorization_code"
    private String code;           // 앱에서 받은 authorizationCode
    private String redirectUri;    // 서버에 등록된 redirect URI
    private String clientId;       // 서비스의 client_id
    private String clientSecret;   // p8 기반으로 서버에서 생성한 client_secret
    private String codeVerifier;   // PKCE
}