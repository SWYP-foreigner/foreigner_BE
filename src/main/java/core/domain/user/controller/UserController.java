package core.domain.user.controller;

import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.service.AppleAuthService;
import core.global.service.GoogleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;



@Tag(name = "User", description = "사용자 관련 API")
@RestController
@RequestMapping("api/v1/member")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleService googleService;
    private final AppleAuthService service;


    @GetMapping("/google/callback")
    public String handleGoogleLogin(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String state) {
        // query parameter 출력
        System.out.println("Google Login Response received!");
        System.out.println("Authorization Code: " + code);
        System.out.println("State: " + state);

        // 단순 확인용으로 response 그대로 반환
        return "Received code: " + code + ", state: " + state;
    }

    @PostMapping("/google/doLogin")
    @Operation(summary = "구글 로그인(웹 API)",
            description = "Swagger에서 테스트할 수 있도록 앱 인증 코드를 사용합니다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<?> googleTestLogin(
            @Parameter(description = "구글 Access Token", required = true)
            @RequestBody GoogleTestReq req) {

        log.info("[GoogleLogin] 요청 수신 - AccessToken: {}", req.getAccessToken());

        // 이 엔드포인트는 토큰 교환 과정을 건너뛰고 바로 사용자 프로필 조회를 시작합니다.
        GoogleProfileDto profile = googleService.getGoogleProfile(req.getAccessToken());
        log.info("[GoogleLogin] 구글 프로필 조회 완료 - sub: {}, email: {}", profile.getSub(), profile.getEmail());

        // 2. 소셜 ID로 기존 사용자 찾기 또는 신규 사용자 생성
        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            log.info("[GoogleLogin] 신규 사용자 생성 - sub: {}, email: {}", profile.getSub(), profile.getEmail());
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        } else {
            log.info("[GoogleLogin] 기존 사용자 로그인 - userId: {}, email: {}", originalUser.getId(), originalUser.getEmail());
        }

        // 3. 사용자 정보로 JWT 토큰 생성
        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());
        log.info("[GoogleLogin] JWT 토큰 생성 완료 - email: {}", originalUser.getEmail());

        // 4. 로그인 정보 반환
        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);

        log.info("[GoogleLogin] 로그인 성공 - userId: {}, email: {}", originalUser.getId(), originalUser.getEmail());

        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }


    @PostMapping("/google/AppLogin")
    @Operation(summary = "구글 앱 로그인 API", description = "Swagger에서 테스트할 수 있도록 앱 인증 코드를 사용합니다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<?> googleLogin(
            @Parameter(description = "구글 로그인 요청 데이터", required = true)
            @RequestBody GoogleLoginReq req) {

        // 1. 구글 인증 코드를 Access Token으로 교환 (웹용 메서드 사용)
        // PKCE는 모바일 앱에 적합하며, Swagger 테스트는 웹 환경이므로 일반 교환 메서드를 사용합니다.
        AccessTokenDto accessTokenDto = googleService.exchangeCodeWithPkce(req.getCode(), req.getCodeVerifier(), req.getPlatform());

        // 2. Access Token으로 사용자 프로필 정보 조회
        GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

        // 3. 소셜 ID로 기존 사용자 찾기 또는 신규 사용자 생성
        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        }

        // 4. 사용자 정보로 JWT 토큰 생성
        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

        // 5. 로그인 정보 반환
        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);
        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }


    /**
     * 권장: Authorization Code + PKCE 플로우
     */
    @PostMapping("apple/doLogin")
    @Operation(summary = "애플 로그인")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<AppleLoginResult> loginByCode(@RequestBody AppleLoginByCodeRequest req) {
        AppleLoginResult result = service.loginWithAuthorizationCodeOnly(
                req.getAuthorizationCode(),
                req.getCodeVerifier(),
                req.getRedirectUri(),
                req.getNonce() // raw nonce (서버에서 sha256 후 검증)
        );
        return ResponseEntity.ok(result);
    }

    /**
     * refresh token 갱신
     */

    @PostMapping("apple/refresh")
    @Operation(summary = "애플 토큰 갱신")
    @ApiResponse(responseCode = "200", description = "토큰 갱신")
    public ResponseEntity<AppleTokenResponse> refresh(@RequestBody AppleRefreshRequest req) {
        return ResponseEntity.ok(service.refresh(req.getRefreshToken()));
    }

    /**
     * revoke (연동 해제)
     */
    @PostMapping("apple/revoke")
    @Operation(summary = "애플 연동 해제")
    @ApiResponse(responseCode = "200", description = "탈퇴")
    public ResponseEntity<Void> revoke(@RequestBody AppleRevokeApiRequest req) {
        service.revoke(req.getRefreshToken());
        return ResponseEntity.noContent().build();
    }



    @PostMapping(value="/profile/setup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "프로필 설정 (JWT 인증 기반)")
    @ApiResponse(responseCode = "200", description = "프로필 설정 성공")
    public ResponseEntity<User> setupUserProfile(@RequestBody UserUpdateDTO userUpdateDTO,
                                                 @RequestPart(value = "image", required = false)
                                                 @Parameter(
                                                         description = "업로드할 이미지 파일",
                                                         content = @Content(
                                                                 mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                                                                 schema = @Schema(type = "string", format = "binary")
                                                         )
                                                 )
                                                 List<MultipartFile> multipartFiles) {
        // JWT 토큰에서 사용자 정보(email)를 가져옵니다.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userName = authentication.getName(); // JWT 토큰의 subject에 저장된 email

        User updatedUser = userService.setupUserNameProfile(userName, userUpdateDTO,multipartFiles);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * 개발 및 테스트 용도의 프로필 설정 API.
     * JWT 인증 없이 userId를 직접 경로 변수로 받습니다.
     * 실제 운영 환경에서는 사용하지 않는 것이 좋습니다.
     */
    @PostMapping(value = "/profile/setup/{userId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "프로필 설정 (테스트용)", description = "개발 및 테스트 용도로 사용자의 ID를 직접 받아 프로필을 설정합니다.")
    @ApiResponse(responseCode = "200", description = "프로필 설정 성공")
    public ResponseEntity<User> setupUserProfileForTest(@PathVariable Long userId,
                                                        @RequestBody UserUpdateDTO userUpdateDTO,
                                                        @RequestPart(value = "image", required = false)
                                                        @Parameter(
                                                                description = "업로드할 이미지 파일",
                                                                content = @Content(
                                                                        mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                                                                        schema = @Schema(type = "string", format = "binary")
                                                                )
                                                        )
                                                        MultipartFile image) {
        User updatedUser = userService.setupUserProfile(userId, userUpdateDTO,image);
        return ResponseEntity.ok(updatedUser);
    }

}
