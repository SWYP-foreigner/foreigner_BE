package core.global.controller;

import core.domain.chat.dto.ChatUserProfileResponse;
import core.domain.user.dto.UserResponseDto;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserService;
import core.global.config.CustomUserDetails;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.enums.Ouathplatform;
import core.global.service.AppleAuthService;
import core.global.service.GoogleService;
import core.global.service.PasswordService;
import core.global.service.RedisService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    private final PasswordService passwordService;
    private final AppleAuthService appleAuthService;

    @GetMapping("/google/callback")
    public String handleGoogleLogin(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String state) {
        System.out.println("Google Login Response received!");
        System.out.println("Authorization Code: " + code);
        System.out.println("State: " + state);
        return "Received code: " + code + ", state: " + state;
    }
    @Operation(summary = "구글 소셜 로그인", description = "앱에서 받은 인증 코드로 구글 로그인을 처리하고 JWT를 발급합니다.")
    @PostMapping("/google/app-login")
    public ResponseEntity<ApiResponse<LoginResponseDto>> googleLogin(
            @Parameter(description = "구글 로그인 요청 데이터", required = true)
            @RequestBody GoogleLoginReq req) {

        log.info("--- [구글 앱 로그인] API 요청 수신 ---");

        try {
            log.info("1. 구글과 인증 코드를 교환하여 액세스 토큰을 받는 중...");
            AccessTokenDto accessTokenDto = googleService.exchangeCode(req.getCode());

            log.info("2. 받은 액세스 토큰으로 구글 사용자 프로필 정보를 조회하는 중...");
            GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

            log.info("3. 데이터베이스에 기존 사용자가 있는지 확인하는 중...");
            User originalUser = userService.getUserBySocialIdAndProvider(profile.getSub(), String.valueOf(Ouathplatform.GOOGLE));

            if (originalUser == null) {
                log.info("새로운 사용자입니다. 소셜 ID로 계정 생성");
                originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), String.valueOf(Ouathplatform.GOOGLE));
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

            boolean isNewUserResponse = originalUser.isNewUser();
            log.info("이 사용자는 새로운 유저입니까? (isNewUser DB 값): {}", isNewUserResponse);

            LoginResponseDto responseDto = new LoginResponseDto(originalUser.getId(), accessToken, refreshToken, isNewUserResponse);

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

        Long UserId = jwtTokenProvider.getUserIdFromRefreshToken(refreshToken);
        Optional<User> userOptional = userrepository.getUserById(UserId);

        if (userOptional.isEmpty()) {
            log.error("토큰의 ID({})로 사용자를 찾을 수 없음", UserId);
            return new ResponseEntity<>(ApiResponse.fail("User not found"), HttpStatus.NOT_FOUND);
        }

        User user = userOptional.get();

        String storedRefreshToken = redisService.getRefreshToken(user.getId());
        log.warn("사용자의 요청한 리프레쉬 토큰 : {}",refreshToken);
        log.warn("레디스의 요청한 리프레쉬 토큰 : {}",storedRefreshToken);
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

    @Operation(summary = "애플 소셜 로그인", description = "앱에서 받은 identityToken으로 애플 로그인을 처리하고 JWT를 발급합니다.")
    @PostMapping("/apple/app-login")
    public ResponseEntity<ApiResponse<LoginResponseDto>> loginWithApple(
            @Parameter(description = "Apple 로그인 요청 데이터", required = true)
            @RequestBody @Valid AppleLoginByCodeRequest req) {

        log.info("--- [Apple 앱 로그인] API 요청 수신 ---");
        try {
            LoginResponseDto responseDto = appleAuthService.login(req);

            log.info("--- [Apple 앱 로그인] 처리 완료. 사용자 ID: {}", responseDto.userId());
            return ResponseEntity.ok(ApiResponse.success(responseDto));

        } catch (Exception e) {
            log.error("--- [Apple 앱 로그인] 로그인 처리 중 오류 발생 ---", e);
            ApiResponse<LoginResponseDto> errorResponse = ApiResponse.fail("로그인 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }




    @PostMapping("/signup")
    @Operation(summary = "일반 회원가입")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest req) {
        userService.signup(req);
        return ResponseEntity.ok().build();
    }


    @PostMapping("/send-verification-email")
    @Operation(summary = "이메일 인증 코드 발송")
    public ResponseEntity<ApiResponse<String>> sendVerificationEmail(@RequestBody EmailRequest request) {
        String tag = (request.getLang() == null || request.getLang().isBlank()) ? "en" : request.getLang();
        Locale locale = Locale.forLanguageTag(tag);   // "en" 기본
        userService.sendEmailVerificationCode(request.getEmail(), locale);
        return ResponseEntity.ok(ApiResponse.success("인증 코드가 이메일로 전송되었습니다."));
    }


    @PostMapping("/verify-code")
    @Operation(summary = "이메일 인증 코드 검증.")
    public ResponseEntity<ApiResponse<Boolean>> verifyEmailCode(@RequestBody EmailVerificationRequest request) {
        boolean isVerified = userService.verifyEmailCode(request);
        return ResponseEntity.ok(ApiResponse.success(isVerified));
    }

    @PostMapping("/doLogin")
    @Operation(summary = "일반 로그인")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody EmailLoginDto req) {
        AuthResponse response = userService.login(req);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/email/check")
    @Operation(summary = "이메일 가입 중복 여부 확인")
    public ResponseEntity<ApiResponse<EmailCheckResponse>> checkRepeat(@RequestBody EmailCheckRequest request) {
        boolean exists = userService.existsByEmail(request.getEmail());

        String message = exists ? "중복된 이메일입니다." : "사용 가능한 이메일입니다.";
        EmailCheckResponse result = new EmailCheckResponse(exists, message);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "비밀번호 재설정 메일 발송")
    public ResponseEntity<Void> forgotPassword(@RequestBody EmailRequest request) {
        Locale loc = (request.getLang() == null || request.getLang().isBlank())
                ? LocaleContextHolder.getLocale()
                : Locale.forLanguageTag(request.getLang());

        passwordService.sendEmailVerificationCode(request.getEmail(), loc);

        return ResponseEntity.ok().build();
    }


    @PostMapping("/password/reset-by-code")
    @Operation(summary = " 비밀번호 재설정")
    public ResponseEntity<Void> resetPasswordByCode(@RequestBody ResetPasswordByCodeRequest req) {
        passwordService.verifyCodeAndResetPassword(req.getEmail(), req.getNewPassword());
        return ResponseEntity.ok().build();
    }


    @PatchMapping("/profile/setup")
    @Operation(summary = "처음 회원가입시 프로필 이미지랑 함께 자기소개 작성 ", description = "현재 사용자의 프로필 정보를 세팅합니다.")
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
    @Operation(summary = "회원 탈퇴 API", description = "현재 로그인한 사용자의 계정을 삭제합니다..")
    public ResponseEntity<Void> withdraw(HttpServletRequest request) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        String authHeader = request.getHeader("Authorization");
        String accessToken = authHeader.substring(7);
        userService.withdrawUser(userId, accessToken);
        return ResponseEntity.noContent().build();
    }

    /**
     * 특정 사용자 한 명의 프로필 정보를 조회합니다.
     * @param userId 조회할 사용자의 ID
     * @return UserResponseDto 형태의 사용자 정보
     */
    @GetMapping("/{userId}/info")
    public ResponseEntity<UserResponseDto> getUserProfile(@PathVariable("userId") Long userId) {
        UserResponseDto userProfile = userService.findUserProfile(userId);
        return ResponseEntity.ok(userProfile);
    }

    /**
     * 여러 사용자의 프로필 정보를 한 번에 조회합니다.
     * @param userIds 조회할 사용자 ID 리스트
     * @return UserResponseDto 리스트 형태의 사용자 정보
     */
    @GetMapping("/infos")
    public ResponseEntity<List<UserResponseDto>> getUsersInfo(@RequestParam("userIds") List<Long> userIds) {
        List<UserResponseDto> userProfiles = userService.findUsersProfiles(userIds);
        return ResponseEntity.ok(userProfiles);
    }

    @Operation(summary = "유저 프로필 조회", description = "userId를 통해 유저의 상세 프로필 정보를 조회합니다.")
    @GetMapping("/{userId}/chat_profile")
    public ResponseEntity<ApiResponse<ChatUserProfileResponse>> getUserChatProfile(@PathVariable Long userId) {
        ChatUserProfileResponse response = userService.getUserChatProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
