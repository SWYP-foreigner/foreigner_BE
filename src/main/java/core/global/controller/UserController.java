package core.global.controller;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.image.service.ImageService;
import core.global.service.AppleAuthService;
import core.global.service.GoogleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;



@Tag(name = "User", description = "사용자 관련 API")
@RestController
@RequestMapping("/api/v1/member")
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

        System.out.println("Google Login Response received!");
        System.out.println("Authorization Code: " + code);
        System.out.println("State: " + state);


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


        GoogleProfileDto profile = googleService.getGoogleProfile(req.getAccessToken());
        log.info("[GoogleLogin] 구글 프로필 조회 완료 - sub: {}, email: {}", profile.getSub(), profile.getEmail());


        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            log.info("[GoogleLogin] 신규 사용자 생성 - sub: {}, email: {}", profile.getSub(), profile.getEmail());
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        } else {
            log.info("[GoogleLogin] 기존 사용자 로그인 - userId: {}, email: {}", originalUser.getId(), originalUser.getEmail());
        }


        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());
        log.info("[GoogleLogin] JWT 토큰 생성 완료 - email: {}", originalUser.getEmail());


        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);

        log.info("[GoogleLogin] 로그인 성공 - userId: {}, email: {}", originalUser.getId(), originalUser.getEmail());

        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }


    @PostMapping("/google/app-login")
    @Operation(summary = "구글 앱 로그인 API", description = "React Native 앱에서 받은 인증 코드를 사용합니다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<?> googleLogin(
            @Parameter(description = "구글 로그인 요청 데이터", required = true)
            @RequestBody GoogleLoginReq req) {

        log.info("--- [구글 앱 로그인] API 요청 수신 ---");
        log.info("요청 데이터: {}", req);
        log.info("앱으로부터 받은 인증 코드: {}", req.getCode());

        try {
            log.info("1. 구글과 인증 코드를 교환하여 액세스 토큰을 받는 중...");
            AccessTokenDto accessTokenDto = googleService.exchangeCode(req.getCode());
            log.info("액세스 토큰 교환 성공. 받은 토큰: {}", accessTokenDto.getAccess_token());

            log.info("2. 받은 액세스 토큰으로 구글 사용자 프로필 정보를 조회하는 중...");
            GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());
            log.info("사용자 프로필 조회 성공. 사용자 ID(sub): {}, 이메일: {}", profile.getSub(), profile.getEmail());

            log.info("3. 데이터베이스에 기존 사용자가 있는지 확인하는 중...");
            User originalUser = userService.getUserBySocialId(profile.getSub());
            if (originalUser == null) {
                log.info("새로운 사용자입니다. 소셜 ID: {}, 이메일: {} 로 계정 생성", profile.getSub(), profile.getEmail());
                originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
                log.info("새로운 사용자 계정 생성 완료. 사용자 ID: {}", originalUser.getId());
            } else {
                log.info("기존 사용자 발견. 사용자 ID: {}", originalUser.getId());
            }

            log.info("4. 인증된 사용자를 위한 새로운 JWT 토큰을 생성하는 중...");
            String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

            Map<String, Object> loginInfo = new HashMap<>();
            loginInfo.put("id", originalUser.getId());
            loginInfo.put("token", jwtToken);
            log.info("5. 로그인 프로세스 완료. 사용자 ID와 JWT 토큰을 반환합니다.");
            log.info("--- [구글 앱 로그인] API 요청 처리 성공 ---");

            return new ResponseEntity<>(loginInfo, HttpStatus.OK);

        } catch (Exception e) {
            log.error("--- [구글 앱 로그인] 로그인 처리 중 오류 발생 ---", e);
            // 클라이언트에게 좀 더 명확한 에러 응답을 반환하도록 수정할 수 있습니다.
            return new ResponseEntity<>("로그인 실패: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * 권장: Authorization Code + PKCE 플로우
     */
    @PostMapping("/apple/doLogin")
    @Operation(summary = "애플 로그인")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<AppleLoginResult> loginByCode(@RequestBody AppleLoginByCodeRequest req) {
        AppleLoginResult result = service.loginWithAuthorizationCodeOnly(
                req.getAuthorizationCode(),
                req.getCodeVerifier(),
                req.getRedirectUri(),
                req.getNonce()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * refresh token 갱신
     */

    @PostMapping("/apple/refresh")
    @Operation(summary = "애플 토큰 갱신")
    @ApiResponse(responseCode = "200", description = "토큰 갱신")
    public ResponseEntity<AppleTokenResponse> refresh(@RequestBody AppleRefreshRequest req) {
        return ResponseEntity.ok(service.refresh(req.getRefreshToken()));
    }

    /**
     * revoke (연동 해제)
     */
    @PostMapping("/apple/revoke")
    @Operation(summary = "애플 연동 해제")
    @ApiResponse(responseCode = "200", description = "탈퇴")
    public ResponseEntity<Void> revoke(@RequestBody AppleRevokeApiRequest req) {
        service.revoke(req.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/profile/setup")
    public ResponseEntity<UserUpdateDTO> updateProfile(@RequestBody UserUpdateDTO dto) {
        UserUpdateDTO response = userService.setupUserProfile(dto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/image")
    public ResponseEntity<Void> deleteProfileImage() {
        userService.deleteProfileImage();
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자 프로필 조회
     */
    @GetMapping("/profile/setting")
    @Operation(summary = "프로필 조회", description = "현재 사용자의 프로필 정보를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "프로필 조회 성공")
    public ResponseEntity<UserUpdateDTO> getProfile() {
        UserUpdateDTO response = userService.getUserProfile();
        return ResponseEntity.ok(response);
    }




}
