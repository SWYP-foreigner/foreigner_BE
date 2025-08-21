package core.global.service;

import core.global.dto.AccessTokenDto;
import core.global.dto.GoogleProfileDto;
import core.global.enums.DeviceType;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 구글 OAuth 2.0 인증 및 사용자 정보 조회를 위한 통합 서비스 클래스.
 * 웹 애플리케이션용 일반 인증 코드 흐름과 모바일 클라이언트용 PKCE 흐름을 모두 지원합니다.
 * RestClient를 사용하여 HTTP 통신을 처리합니다.
 */
@Service
@RequiredArgsConstructor
public class GoogleService {

    @Value("${oauth.google.web.client-id}")
    private String webClientId;
    @Value("${oauth.google.web.client-secret}")
    private String webClientSecret;
    @Value("${oauth.google.web.redirect-uri}")
    private String webRedirectUri;

    // application.yml에서 안드로이드 클라이언트 정보를 주입받습니다.
    @Value("${oauth.google.android.client-id}")
    private String androidClientId;
    @Value("${oauth.google.android.redirect-uri}")
    private String androidRedirectUri;

    @Value("${oauth.google.ios.client-id}")
    private String iosClientId;
    @Value("${oauth.google.ios.redirect-uri}")
    private String iosRedirectUri;

    // HTTP 통신을 위한 RestClient 인스턴스를 생성합니다.
    private final RestClient restClient = RestClient.create();


    public AccessTokenDto exchangeCode(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("code", code);
        formData.add("client_id", webClientId);
        formData.add("client_secret", webClientSecret);
        formData.add("redirect_uri", webRedirectUri);

        ResponseEntity<AccessTokenDto> response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .toEntity(AccessTokenDto.class);

        return response.getBody();
    }
    /**
     * 모바일 앱용: Authorization Code와 PKCE를 사용하여 구글로부터 Access Token을 교환합니다.
     * 이 방식은 클라이언트 시크릿을 노출하지 않아도 되므로 모바일 앱에 적합합니다.
     *
     * @param code         앱에서 받은 Authorization Code
     * @param codeVerifier 앱에서 생성한 code_verifier (PKCE의 핵심)
     * @param platform     요청을 보낸 클라이언트의 플랫폼 (ANDROID)
     * @return 구글로부터 받은 Access Token 정보 (AccessTokenDto)
     */
    public AccessTokenDto exchangeCodeWithPkce(String code, String codeVerifier, DeviceType platform) {
        String clientId;
        String redirectUri;

        // 플랫폼에 따라 클라이언트 ID와 리디렉션 URI를 선택합니다.
        switch (platform) {
            case ANDROID -> {
                clientId = androidClientId;
                redirectUri = androidRedirectUri;
            }
            case IOS -> {
                clientId = iosClientId;
                redirectUri = iosRedirectUri;
            }
            default ->throw new BusinessException(ErrorCode.PLACE_NOT_FOUND);
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("code", code);
        formData.add("client_id", clientId);
        formData.add("code_verifier", codeVerifier);
        formData.add("redirect_uri", redirectUri);

        ResponseEntity<AccessTokenDto> response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .toEntity(AccessTokenDto.class);

        return response.getBody();
    }

    /**
     * Access Token을 사용하여 구글 OIDC(OpenID Connect)의 userinfo 엔드포인트에서
     * 사용자 프로필 정보를 조회합니다.
     *
     * @param accessToken 구글로부터 받은 Access Token
     * @return 사용자의 프로필 정보 (GoogleProfileDto)
     */
    public GoogleProfileDto getGoogleProfile(String accessToken) {
        ResponseEntity<GoogleProfileDto> response = restClient.get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toEntity(GoogleProfileDto.class);
        return response.getBody();
    }





}
