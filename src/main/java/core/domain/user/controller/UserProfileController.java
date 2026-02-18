package core.domain.user.controller;

import core.domain.user.dto.UserOnlineStatusResponse;
import core.domain.user.dto.UserProfileCardResponse;
import core.domain.user.dto.UserProfileGroupChatRoomResponse;
import core.domain.user.dto.UserProfilePostResponse;
import core.domain.user.service.UserService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile (타인 조회)", description = "상대방 유저의 프로필 카드, 게시글, 그룹, 온라인 상태 조회 API")
@RestController
@RequestMapping("/api/v1/member") // 기존 URL 유지를 위해 동일하게 설정
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.METHOD_NOT_ALLOWED})
@RequiredArgsConstructor
@Slf4j
public class UserProfileController {

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

    // =================================================================================
    // 2. Linked Space (참여 중인 그룹 채팅방) - 무한 스크롤
    // =================================================================================
    @Operation(
            summary = "Linked Space 조회 (참여 중인 그룹 채팅방)",
            description = """
                    해당 유저가 현재 참여 중인 **공개 그룹 채팅방(Linked Space)** 목록을 조회합니다.
                    
                    - **방식**: 무한 스크롤 (Slice)
                    - **정렬**: 최근 메시지가 발생한 순서 (`lastMessageSentAt` DESC)
                    - **필터**: `isGroup=true`인 채팅방만 조회됩니다.
                    """
    )
    @Parameters({
            @Parameter(name = "page", description = "페이지 번호 (0부터 시작)", example = "0"),
            @Parameter(name = "size", description = "한 페이지에 조회할 데이터 개수", example = "10"),
            @Parameter(name = "sort", description = "정렬 기준 (기본값: chatRoom.lastMessageSentAt,desc)", example = "chatRoom.lastMessageSentAt,desc")
    })
    @GetMapping("/{userId}/groups")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<Slice<UserProfileGroupChatRoomResponse>>> getUserGroupChats(
            @Parameter(description = "조회할 대상 유저의 ID", example = "1")
            @PathVariable Long userId,

            @Parameter(hidden = true)
            @PageableDefault(size = 10, sort = "chatRoom.lastMessageSentAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Slice<UserProfileGroupChatRoomResponse> response = userService.getUserGroupChatRooms(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // =================================================================================
    // 3. 온라인 상태 조회
    // =================================================================================
    @Operation(
            summary = "유저 실시간 접속 상태(Online Status) 조회",
            description = """
                    해당 유저가 현재 접속 중인지 확인합니다.
                    
                    **[판단 기준]**
                    - 유저의 마지막 활동 시간(`lastSeenAt`)이 **현재 시간 기준 5분 이내**이면 `isOnline: true`
                    - 그렇지 않으면 `isOnline: false`
                    """
    )
    @GetMapping("/{userId}/online-status")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<UserOnlineStatusResponse>> getUserOnlineStatus(
            @Parameter(description = "상태를 확인할 유저의 ID", example = "1")
            @PathVariable Long userId
    ) {
        UserOnlineStatusResponse response = userService.checkUserOnlineStatus(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // =================================================================================
    // 4. 게시글 목록 조회 (무한 스크롤)
    // =================================================================================
    @Operation(
            summary = "작성한 게시글 목록 조회 (무한 스크롤)",
            description = """
                    해당 유저가 작성한 **게시글(Post)** 목록을 조회합니다.
                    
                    **[포함 정보]**
                    - 게시글 내용, 작성일
                    - **썸네일 이미지** (이미지가 여러 개일 경우 0번째 이미지)
                    - **좋아요 수**, **댓글 수**
                    
                    - **정렬**: 최신순 (`createdAt` DESC)
                    """
    )
    @Parameters({
            @Parameter(name = "page", description = "페이지 번호 (0부터 시작)", example = "0"),
            @Parameter(name = "size", description = "한 페이지 조회 개수", example = "10"),
            @Parameter(name = "sort", description = "정렬 기준 (기본값: createdAt,desc)", example = "createdAt,desc")
    })
    @GetMapping("/{userId}/posts")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<Slice<UserProfilePostResponse>>> getUserPosts(
            @Parameter(description = "조회할 대상 유저의 ID", example = "1")
            @PathVariable Long userId,

            @Parameter(hidden = true)
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Slice<UserProfilePostResponse> response = userService.getUserPosts(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}