package core.global.service;



import core.global.dto.AccessTokenDto;
import core.global.dto.GoogleProfileDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class GoogleService {

    @Value("${oauth.google.android.client-id}")
    private String androidClientId;
    @Value("${oauth.google.android.redirect-uri}")
    private String androidRedirectUri;

    @Value("${oauth.google.ios.client-id}")
    private String iosClientId;
    @Value("${oauth.google.ios.redirect-uri}")
    private String iosRedirectUri;

    private final RestClient restClient = RestClient.create();

    public enum Platform { ANDROID, IOS }

    /**
     * Authorization Code + PKCE로 토큰 교환 (client_secret 불필요)
     * @param code         앱에서 받은 authorization code
     * @param codeVerifier 앱에서 생성한 code_verifier
     * @param platform     ANDROID 또는 IOS
     */
    public AccessTokenDto exchangeCodeWithPkce(String code, String codeVerifier, Platform platform) {
        String clientId;
        String redirectUri;

        switch (platform) {
            case ANDROID -> {
                clientId = androidClientId;
                redirectUri = androidRedirectUri;
            }
            case IOS -> {
                clientId = iosClientId;
                redirectUri = iosRedirectUri;
            }
            default -> throw new IllegalArgumentException("지원하지 않습니다. " + platform);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);

        ResponseEntity<AccessTokenDto> response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toEntity(AccessTokenDto.class);

        return response.getBody();
    }

    /** access_token으로 OIDC userinfo 조회 */
    public GoogleProfileDto getGoogleProfile(String accessToken) {
        ResponseEntity<GoogleProfileDto> response = restClient.get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toEntity(GoogleProfileDto.class);
        return response.getBody();
    }
}
