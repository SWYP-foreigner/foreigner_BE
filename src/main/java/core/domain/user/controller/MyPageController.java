package core.domain.user.controller;

import core.domain.user.dto.FollowDTO;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.dto.ApiResponse;
import core.global.enums.ErrorCode;
import core.global.enums.FollowStatus;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
            @Parameter(description = "조회할 팔로우 상태 (서로 맞팔인 상태, 수락안한상태,보낸상태)") @RequestParam FollowStatus status) {

        List<FollowDTO> followingList = followService.getFollowingListByStatus(authentication.getName(), status);

        return ResponseEntity.ok().body(followingList);
    }

    @Operation(summary = "팔로우 요청 수락", description = "나에게 들어온 팔로우 요청을 수락합니다.")
    @PatchMapping("/accept-follow/{fromUserId}")
    public ResponseEntity<ApiResponse<String>> acceptFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID") @PathVariable Long fromUserId) {

        followService.acceptFollow(fromUserId,authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 수락되었습니다."));
    }

    // 이부분 JPA 라 코드 줄이기가 좀
    @Operation(summary = "팔로우 요청 거절", description = "나에게 들어온 팔로우 요청을 거절합니다. ")
    @DeleteMapping("/decline-follow/{fromUserId}")
    public ResponseEntity<ApiResponse<String>> declineFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID")
            @PathVariable Long fromUserId) {

        User toUser = userService.findUserByUsername(authentication.getName());
        followService.declineFollow(fromUserId, toUser.getId());
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 거절되었습니다."));
    }


    @Operation(summary = "친구 끊기(언팔로우) ", description = "로그인한 사용자가 특정 친구를 언팔로우합니다.")
    @DeleteMapping("/users/follow/{friendId}")
    public ResponseEntity<ApiResponse<String>> unfollow(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollow(authentication.getName(), friendId);
        return ResponseEntity.ok(ApiResponse.success("팔로우가 취소되었습니다."));
    }


    @Operation(summary="마이 프로필 수정", description = "내 프로필 내용을 수정합니다.")
    @PatchMapping("/users/")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK, 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않은 요청입니다.")
    })
    public ResponseEntity<ApiResponse<String>> updateProfile(
            Authentication authentication,  @RequestBody UserUpdateDTO dto) {
        userService.updateUser(authentication.getName(), dto);
        return ResponseEntity.ok(ApiResponse.success("프로필이 성공적으로 수정되었습니다."));
    }

    }


