package core.domain.user.controller;

import core.domain.user.dto.FollowDTO;
import core.domain.user.dto.ProfileEditResponseDto;
import core.domain.user.dto.UserProfileEditDto;
import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.appsetting.AppSettingService;
import core.global.appsetting.SupportLinksResponse;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.ImageErrorCodeDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.dto.UserLanguageDTO;
import core.global.enums.FollowStatus;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import core.global.service.TranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@Tag(name = "마이페이지 ", description = "팔로우 한 친구 목록 보기 API")
@RestController
@RequestMapping("/api/v1/mypage")
@RequiredArgsConstructor
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class MyPageController {

    private final UserService userService;
    private final FollowService followService;
    private final TranslationService translationService;
    private final FeatureUsageMetrics featureUsageMetrics;
    private final AppSettingService appSettingService;

    @Operation(summary = "팔로우 요청 보내기", description = "마음에 드는 친구에게 팔로우 요청을 전송합니다.")
    @PostMapping("/follow/{userId}")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED, UserErrorCode.CANNOT_FOLLOW_YOURSELF,  UserErrorCode.FOLLOW_ALREADY_EXISTS})
    public ResponseEntity<ApiResponse<String>> followUser(
            Authentication authentication, @PathVariable Long userId) {
        followService.follow(authentication, userId);
        featureUsageMetrics.recordFollowUsage();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 각 FollowStatus 별 팔로우 목록 조회
     * GET /api/v1/mypage/following?status=ACCEPTED
     */
    @Operation(summary = "팔로우/팔로워 목록 조회", description = "팔로잉 또는 팔로워 목록을 특정 상태(status)로 조회합니다.")
    @GetMapping("/follows")
    public ResponseEntity<List<FollowDTO>> getFollowsByStatus(
            Authentication authentication,
            @Parameter(description = "팔로우 상태 (예: ACCEPTED, PENDING)") @RequestParam FollowStatus status,
            @Parameter(description = "true인 경우 팔로워, false인 경우 팔로잉 목록을 조회") @RequestParam(defaultValue = "false") boolean isFollowers) {

        List<FollowDTO> list = followService.getMyFollowsByStatus(authentication, status, isFollowers);
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().body(list);
    }

    @Operation(summary = "Received/Sent 수 조회", description = "PENDING 상태의 보낸/받은 팔로우 요청 수를 조회합니다.")
    @GetMapping("/follows/pending/count")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<Map<String, Long>> getPendingFollowsCount(Authentication authentication) {
        return ResponseEntity.ok(followService.getPendingFollowCounts(authentication));
    }

    @Operation(
            summary = "로그인 한 사용자의 맞팔 목록(ACCEPTED)",
            description = "ACCEPTED 상태의 팔로우 관계만 가져옵니다."
    )
    @GetMapping("/follows/accepted")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<List<FollowDTO>> getAcceptedFollows(
            Authentication authentication
    ) {
        featureUsageMetrics.recordFollowUsage();
        return ResponseEntity.ok(followService.getMyAcceptedFollows(authentication));
    }

    @Operation(summary = "팔로우 요청 수락", description = "나에게 들어온 팔로우 요청을 수락합니다.")
    @PatchMapping("/accept-follow/{fromUserId}")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.FOLLOWER_NOT_FOUND})
    public ResponseEntity<Void> acceptFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID") @PathVariable Long fromUserId) {

        followService.acceptFollow(authentication, fromUserId);
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "팔로우 요청 거절 (decline) ", description = "나에게 들어온 팔로우 요청을 거절합니다. ")
    @DeleteMapping("/decline-follow/{fromUserId}")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.FOLLOWER_NOT_FOUND})
    public ResponseEntity<Void> declineFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID")
            @PathVariable Long fromUserId) {

        followService.declineFollow(authentication, fromUserId);
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "친구가 되기 전에 PENDING 상태 팔로우 요청 취소",
            description = "팔로우 요청을 취소합니다.")
    @DeleteMapping("/users/follow/{friendId}")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.FOLLOWER_NOT_FOUND})
    public ResponseEntity<Void> unfollowPending(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollow(authentication, friendId);
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "친구가 된 후 ACCEPTED 상태 친구 관계 해제",
            description = "친구 관계를 해제합니다.")
    @DeleteMapping("/users/follow/accepted/{friendId}")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.FOLLOW_NOT_FOUND})
    public ResponseEntity<Void> unfollowAccepted(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollow(authentication, friendId);
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().build();
    }

    @PatchMapping(value = "/profile/edit", consumes = "application/json", produces = "application/json")
    @Operation(
            summary = "마이페이지 프로필 수정",
            description = "기존사용자(USER)와 스킵한 사용자(VISITOR)모두 수정에 사용합니다. 스킵한 VISITOR 유저가 완료할 시에는" +
                          " 응답 객체에 [accessToken, refreshToken]이 포함되어 발급됩니다."
    )
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @ImageErrorCodeDocs({ImageErrorCode.IMAGE_UPLOAD_FAILED, ImageErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR,})
    public ResponseEntity<ProfileEditResponseDto> editProfile(
            @Valid @RequestBody UserProfileEditDto dto
    ) {
        featureUsageMetrics.recordFollowUsage();
        return ResponseEntity.ok(userService.updateUserProfile(dto));
    }

    @PutMapping("/user/language")
    @Operation(summary = "사용자 언어 설정", description = "인증된 사용자의 기본 채팅 언어를 저장합니다.")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<Void> updateUserLanguage(
            Authentication auth,
            @RequestBody UserLanguageDTO dto) {

        translationService.saveUserLanguage(auth, dto.getLanguage());
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "고객 지원 링크 조회 (버그 제보/피드백)",
            description = "서버에서 관리하는 구글 폼 링크(피드백, 버그 제보)를 반환합니다."
    )
    @GetMapping("/support-links")
    public ResponseEntity<SupportLinksResponse> getSupportLinks() {
        SupportLinksResponse response = appSettingService.getSupportLinks();
        return ResponseEntity.ok(response);
    }

}


