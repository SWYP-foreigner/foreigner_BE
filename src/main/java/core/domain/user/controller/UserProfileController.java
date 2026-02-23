package core.domain.user.controller;

import core.domain.board.dto.BoardItem;
import core.domain.chat.dto.ChatRoomResponse;
import core.domain.post.service.PostService;
import core.domain.user.dto.UserOnlineStatusResponse;
import core.domain.user.dto.UserProfileCardResponse;
import core.domain.user.dto.UserProfileGroupChatRoomResponse;
import core.domain.user.dto.UserProfilePostResponse;
import core.domain.user.service.UserService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.common.CommunitySortOption;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Profile (타인 조회)", description = "상대방 유저의 프로필 카드, 게시글, 그룹, 온라인 상태 조회 API")
@RestController
@RequestMapping("/api/v1/member") // 기존 URL 유지를 위해 동일하게 설정
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.METHOD_NOT_ALLOWED})
@RequiredArgsConstructor
@Slf4j
public class UserProfileController {
    private final PostService postService;
    private final UserService userService;
    private final FeatureUsageMetrics featureUsageMetrics;

    // =================================================================================
    // 1. 프로필 카드 기본 정보
    // =================================================================================
    @Operation(
            summary = "유저 프로필 카드 기본 정보 조회",
            description = """
                    특정 유저의 **프로필 카드 상단에 들어가는 핵심 정보**를 조회합니다.
                    
                    **[포함되는 정보]**
                    - 닉네임, 프로필 이미지, 한줄 소개
                    - 국적, 사용 언어, 관심사(Hobby)
                    - 팔로워/팔로잉 수, 총 방문자 수
                    - **나(로그인 유저)와의 팔로우 상태** (NONE, PENDING, ACCEPTED)
                    """
    )
    @GetMapping("/{userId}/info")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<UserProfileCardResponse> getUserProfile(
            @Parameter(description = "조회할 대상 유저의 ID (PK)", example = "1")
            @PathVariable("userId") Long userId,

            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long currentUserId = userDetails.getUserId();
        UserProfileCardResponse userProfile = userService.findCardUserProfile(userId, currentUserId);

        // 지표 수집
        featureUsageMetrics.recordFollowUsage();

        return ResponseEntity.ok(userProfile);
    }

    @Operation(summary = "가입한 그룹 채팅방 목록 조회 (커서 기반)")
    @GetMapping("/profile/{userId}/chat-rooms")
    public ResponseEntity<ApiResponse<CursorPageResponse<UserProfileGroupChatRoomResponse>>> getJoinedChatRooms(
            @PathVariable Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "15") int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUserGroupChatRooms(userId, cursor, size)));
    }


    @Operation(
            summary = "유저 실시간 접속 상태(Online Status) 조회",
            description = """
                    해당 유저가 현재 접속 중인지 확인합니다.
                    
                    **[판단 기준]**
                    - 유저의 마지막 활동 시간(`lastSeenAt`)이 **현재 시간 기준 5분 이내**이면 `isOnline: true`
                    - 그렇지 않으면 `isOnline: false`
                    """
    )
    @GetMapping("/profile/{userId}/online-status")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<UserOnlineStatusResponse>> getUserOnlineStatus(
            @Parameter(description = "상태를 확인할 유저의 ID", example = "1")
            @PathVariable Long userId
    ) {
        UserOnlineStatusResponse response = userService.checkUserOnlineStatus(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "특정 유저의 게시글 목록 조회", description = "특정 유저가 작성한 게시글을 무한 스크롤로 조회합니다.")
    @GetMapping("/profile/{userId}/posts")
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<BoardItem>>> getUserPostList(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "LATEST") CommunitySortOption sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        postService.getUserPostList(userId, sort, cursor, size) // 새로운 서비스 메서드 호출
                ));
    }
}