package core.global.controller;


import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
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
import org.springframework.beans.factory.annotation.Value;
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
@RequestMapping("/api/v1/member")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleService googleService;
    private final AppleAuthService service;
    private final AmazonS3 amazonS3;
    @Value("${ncp.s3.bucket}")
    private String bucketName;

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

        AccessTokenDto accessTokenDto = googleService.exchangeCode(req.getCode());

        GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        }

        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);

        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
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




    @PatchMapping(value = "/profile/setup")
    @Operation(summary = "프로필 최종 반영", description = "사용자 프로필을 업데이트하고 S3 이미지 키를 저장합니다.")
    @ApiResponse(responseCode = "200", description = "프로필 업데이트 성공")
    public ResponseEntity<UserUpdateDTO> updateProfile(@RequestBody UserUpdateDTO dto) {

        // 1) imageKey가 전달되었으면 S3 객체 존재/메타 검증
        if (dto.getImageKey() != null && !dto.getImageKey().isBlank()) {
            validateS3Image(dto.getImageKey());
        }

        // 2) 서비스에 DTO 자체를 넘겨 일관 처리 (imageKey 따로 넘길 필요 없음)
        UserUpdateDTO response = userService.setupUserProfile(dto);
        return ResponseEntity.ok(response);
    }

    /**
     * 이미지가 s3 에 있는 지 찾는 로직
     * @param imageKey
     *  사이즈 검증 (예: 10MB 제한)
     *  Content-Type 검증 (image/*)
     */
    private void validateS3Image(String imageKey) {
        if (!amazonS3.doesObjectExist(bucketName, imageKey)) {
            throw new IllegalArgumentException("S3 object not found for key: " + imageKey);
        }
        ObjectMetadata meta = amazonS3.getObjectMetadata(bucketName, imageKey);

        long size = meta.getContentLength();
        if (size <= 0 || size > 10L * 1024 * 1024) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        }

        String contentType = meta.getContentType();
        log.info(contentType.toLowerCase());
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR);
        }
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

    /**
     * 프로필 이미지 삭제
     */
    @DeleteMapping("/image")
    @Operation(summary = "프로필 이미지 삭제", description = "현재 사용자의 프로필 이미지를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "이미지 삭제 성공")
    public ResponseEntity<Void> deleteProfileImage() {
        userService.deleteProfileImage();
        return ResponseEntity.noContent().build();
    }
    /**
     * 로그인 없는 헤더에 이메일 추가후 바로 정보 수정하는 방식
     */

    @PatchMapping(value = "/profile/test/edit", consumes = "application/json", produces = "application/json")
    @Operation(
            summary = "프로필 수정(로그인 없이, 헤더에 이메일 넣어서 테스트)",
            description = "X-User-Email 헤더의 이메일로 사용자를 식별하여 프로필을 부분 수정(PATCH)합니다."
    )
    public ResponseEntity<UserUpdateDTO> editProfileForTest(
            @RequestHeader(value = "X-User-Email") String email,   // 수정은 명확히 required 로
            @RequestBody UserUpdateDTO dto
    ) {
        UserUpdateDTO response = userService.updateUserProfileTest(email, dto);
        return ResponseEntity.ok(response);
    }


    /**
     * 로그인 없는 사용자 프로필 조회
     */
    @GetMapping("/profile/test")
    @Operation(summary = "프로필 조회(로그인 없이  헤더에 이메일 넣어서 테스트)", description = "현재 사용자의 프로필 정보를 조회합니다.")
    public ResponseEntity<UserUpdateDTO> getProfile(
            @RequestHeader(value = "X-User-Email", required = false) String email
    ) {
        UserUpdateDTO response = userService.getUserProfile(email);
        return ResponseEntity.ok(response);
    }

    /**
     * 로그인 없이 테스트
     * 프로필 이미지 삭제
     * 우선순위: X-User-Email 헤더 > ?firstName&lastName 쿼리 > SecurityContext
     */
    @DeleteMapping("/profile/test/image/delete")
    @Operation(summary = "프로필 이미지 삭제(로그인 없이 헤더에 이메일 넣어서 테스트)", description = "현재 사용자의 프로필 이미지를 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "이미지 삭제 성공")
    public ResponseEntity<Void> deleteProfileImage(
            @RequestHeader(value = "X-User-Email", required = false) String email
    ) {
        userService.deleteProfileImageTest(email);
        return ResponseEntity.noContent().build();
    }


}
