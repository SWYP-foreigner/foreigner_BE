package core.global.controller;

import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserService;
import core.global.config.CustomUserDetails;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.image.service.ImageService;
import core.global.service.AppleAuthService;
import core.global.service.GoogleService;
import core.global.service.RedisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
    private final RedisService redisService;
    private final UserRepository userrepository;
    @GetMapping("/google/callback")
    public String handleGoogleLogin(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String state) {
        System.out.println("Google Login Response received!");
        System.out.println("Authorization Code: " + code);
        System.out.println("State: " + state);
        return "Received code: " + code + ", state: " + state;
    }

    @PostMapping("/google/app-login")
    @Operation(summary = "구글 앱 로그인 API", description = "React Native 앱에서 받은 인증 코드를 사용합니다.")

    public ResponseEntity<ApiResponse<LoginResponseDto>> googleLogin(
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
            User originalUser = userService.getUserBySocialIdAndProvider(profile.getSub(), "GOOGLE");
            if (originalUser == null) {
                log.info("새로운 사용자입니다. 소셜 ID: {}, 이메일: {} 로 계정 생성", profile.getSub(), profile.getEmail());
                originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
                log.info("새로운 사용자 계정 생성 완료. 사용자 ID: {}", originalUser.getId());
            } else {
                log.info("기존 사용자 발견. 사용자 ID: {}", originalUser.getId());
            }
            log.info("4. 인증된 사용자를 위한 새로운 JWT 토큰을 생성하는 중...");
            String accessToken = jwtTokenProvider.createAccessToken(originalUser.getId(), originalUser.getEmail());
            String refreshToken = jwtTokenProvider.createRefreshToken(originalUser.getId());

            Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);

            long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();

            redisService.saveRefreshToken(originalUser.getId(), refreshToken, expirationMillis);

            LoginResponseDto responseDto = new LoginResponseDto(originalUser.getId(), accessToken, refreshToken);

            return ResponseEntity.ok(ApiResponse.success(responseDto));

        } catch (Exception e) {
            log.error("--- [구글 앱 로그인] 로그인 처리 중 오류 발생 ---", e);
            ApiResponse<LoginResponseDto> errorResponse = ApiResponse.fail("로그인 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃 API", description = "현재 사용자의 액세스 토큰을 블랙리스트에 등록하고, 리프레시 토큰을 삭제합니다.")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String accessToken = authHeader.substring(7);
        long expiration = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();

        redisService.blacklistAccessToken(accessToken, expiration);

        Long userId = jwtTokenProvider.getUserIdFromAccessToken(accessToken);
        redisService.deleteRefreshToken(userId);

        log.info("사용자 {} 로그아웃 처리 완료.", userId);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/refresh")
    @Operation(summary = "토큰 재발급 API", description = "리프레시 토큰으로 새로운 액세스 토큰과 리프레시 토큰을 발급합니다.")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refreshToken(@RequestBody TokenRefreshRequest request) {
        log.info("--- [토큰 재발급] 요청 수신 ---");
        String refreshToken = request.refreshToken();


        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("유효하지 않은 리프레시 토큰 요청: {}", refreshToken);
            return new ResponseEntity<>(ApiResponse.fail("Invalid or expired refresh token"), HttpStatus.UNAUTHORIZED);
        }

        Long UserId  = jwtTokenProvider.getUserIdFromAccessToken(refreshToken);
        Optional<User> userOptional = userrepository.getUserById(UserId);

        if (userOptional.isEmpty()) {
            log.error("토큰의 ID({})로 사용자를 찾을 수 없음", UserId);
            return new ResponseEntity<>(ApiResponse.fail("User not found"), HttpStatus.NOT_FOUND);
        }

        User user = userOptional.get();

        String storedRefreshToken = redisService.getRefreshToken(user.getId());

        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {


            log.warn("Redis의 리프레시 토큰과 불일치. 탈취 가능성. 사용자 ID: {}", user.getId());
            redisService.deleteRefreshToken(user.getId());
            return new ResponseEntity<>(ApiResponse.fail("Refresh token mismatch or blacklisted"), HttpStatus.UNAUTHORIZED);
        }
        redisService.deleteRefreshToken(user.getId());
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(newRefreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), newRefreshToken, expirationMillis);

        log.info("--- [토큰 재발급] 완료. 사용자 ID: {} ---", user.getId());

        TokenRefreshResponse responseDto = new TokenRefreshResponse(newAccessToken, newRefreshToken,user.getId());
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/apple/doLogin")
    @Operation(summary = "애플 로그인")
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
     * revoke (연동 해제)
     */
    @PostMapping("/apple/revoke")
    @Operation(summary = "애플 연동 해제")
    public ResponseEntity<Void> revoke(@RequestBody AppleRevokeApiRequest req) {
        service.revoke(req.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/profile/setup")
    @Operation(summary = "프로필 이미지 셋업", description = "현재 사용자의 프로필 정보를 세팅합니다.")
    public ResponseEntity<UserUpdateDTO> updateProfile(@RequestBody UserUpdateDTO dto) {
        UserUpdateDTO response = userService.setupUserProfile(dto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/image")
    @Operation(summary = "프로필 이미지 삭제", description = "현재 사용자의 프로필 정보를 삭제합니다.")
    public ResponseEntity<Void> deleteProfileImage() {
        userService.deleteProfileImage();
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자 프로필 조회
     */
    @GetMapping("/profile/setting")
    @Operation(summary = "프로필 조회", description = "현재 사용자의 프로필 정보를 조회합니다.")
    public ResponseEntity<UserUpdateDTO> getProfile() {
        UserUpdateDTO response = userService.getUserProfile();
        return ResponseEntity.ok(response);
    }
    @DeleteMapping("/withdraw")
    @Operation(summary = "회원 탈퇴 API", description = "현재 로그인한 사용자의 계정을 삭제합니다.")
    public ResponseEntity<Void> withdraw(HttpServletRequest request) {
        try {

            CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            Long userId = principal.getUserId();
            String authHeader = request.getHeader("Authorization");
            String accessToken = authHeader.substring(7);

            userService.deleteUser(userId);
            redisService.deleteRefreshToken(userId);
            long expiration = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();
            redisService.blacklistAccessToken(accessToken, expiration);

            log.info("사용자 {} 계정 및 관련 토큰 삭제 완료.", userId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("회원 탈퇴 처리 중 오류 발생", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
