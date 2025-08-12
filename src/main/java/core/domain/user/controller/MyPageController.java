package core.domain.user.controller;

import core.domain.user.dto.FollowDTO;
import core.domain.user.entity.User;
import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.dto.ApiResponse;
import core.global.enums.FollowStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Tag(name = "마이페이지 ", description = "팔로우 한 친구 목록 보기 API")
@RestController
@RequestMapping("/api/v1/mypage")
@RequiredArgsConstructor
public class MyPageController {


    private final UserService userService;
    private final FollowService followService;



    /**
     * 각 FollowStatus 별 팔로우 목록 조회
     * GET /api/v1/mypage/following?status=ACCEPTED
     */
    @Operation(summary = "팔로우 목록 조회", description = "특정 상태(status)의 팔로우 목록을 조회합니다.")
    @GetMapping("/following")
    public ResponseEntity<List<FollowDTO>> getFollowingListByStatus(
            Authentication authentication,
            @Parameter(description = "조회할 팔로우 상태 (PENDING, ACCEPTED)") @RequestParam FollowStatus status) {

        User fromUser = userService.findUserByUsername(authentication.getName());
        List<FollowDTO> followingList = followService.getFollowingListByStatus(fromUser.getId(), status);

        return ResponseEntity.ok().body(followingList);
    }



    /**
     * 내가 보낸 팔로우 요청 목록 조회 (PENDING 상태)
     * GET /api/v1/mypage/following/pending
     */
    @Operation(summary = "내가 보낸 팔로우 요청 목록 조회", description = "내가 보냈지만 아직 수락되지 않은 팔로우 요청(PENDING 상태) 목록을 조회합니다.")
    @GetMapping("/following/pending")
    public ResponseEntity<List<FollowDTO>> getPendingFollowingList(Authentication authentication) {
        User user = userService.findUserByUsername(authentication.getName());
        List<FollowDTO> pendingFollowing = followService.getFollowingListByStatus(user.getId(), FollowStatus.PENDING);

        return ResponseEntity.ok().body(pendingFollowing);
    }

    /**
     * 팔로우 요청 수락
     * PATCH /api/v1/mypage/accept-follow/{fromUserId}
     */
    @Operation(summary = "팔로우 요청 수락", description = "나에게 들어온 팔로우 요청을 수락합니다.")
    @PatchMapping("/accept-follow/{fromUserId}")
    public ResponseEntity<ApiResponse<String>> acceptFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID") @PathVariable Long fromUserId) {

        User toUser = userService.findUserByUsername(authentication.getName());
        followService.acceptFollow(fromUserId, toUser.getId());
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 수락되었습니다."));
    }

    /**
     * 친구 끊기 (Unfollow)
     * DELETE /api/v1/mypage/users/follow/{friendId}
     */
    @Operation(summary = "친구 끊기", description = "로그인한 사용자가 특정 친구를 언팔로우합니다.")
    @DeleteMapping("/users/follow/{friendId}")
    public ResponseEntity<String> unfollow(
            Authentication authentication,
            @Parameter(description = "언팔로우할 친구의 ID") @PathVariable("friendId") Long friendId) {

        // 인증된 사용자 (언팔로우를 요청하는 사람)
        User fromUser = userService.findUserByUsername(authentication.getName());

        // 언팔로우 대상 사용자
        User toUser = userService.findById(friendId);

        // Service에 ID를 전달하여 언팔로우 로직 실행
        followService.unfollow(fromUser.getId(), toUser.getId());

        return ResponseEntity.ok("팔로우가 취소되었습니다.");
    }
}
