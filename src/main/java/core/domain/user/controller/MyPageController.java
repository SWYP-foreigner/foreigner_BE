package core.domain.user.controller;

import core.domain.user.dto.FollowDTO;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.CustomUserDetails;
import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.dto.ApiResponse;
import core.global.enums.ErrorCode;
import core.global.enums.FollowStatus;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Tag(name = "마이페이지 ", description = "팔로우 한 친구 목록 보기 API")
@RestController
@RequestMapping("/api/v1/mypage")
@RequiredArgsConstructor
@Slf4j
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

        List<FollowDTO> followingList = followService.getMyFollowingByStatus(authentication, status);

        return ResponseEntity.ok().body(followingList);
    }

    @Operation(summary = "팔로우 요청 수락", description = "나에게 들어온 팔로우 요청을 수락합니다.")
    @PatchMapping("/accept-follow/{fromUserId}")
    public ResponseEntity<ApiResponse<String>> acceptFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID") @PathVariable Long fromUserId) {

        followService.acceptFollow(authentication,fromUserId);
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 수락되었습니다."));
    }

    @Operation(summary = "팔로우 요청 거절", description = "나에게 들어온 팔로우 요청을 거절합니다. ")
    @DeleteMapping("/decline-follow/{fromUserId}")
    public ResponseEntity<ApiResponse<String>> declineFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID")
            @PathVariable Long fromUserId) {

        followService.declineFollow(authentication,fromUserId);
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 거절되었습니다."));
    }


    @Operation(summary = "친구 끊기(언팔로우) ", description = "로그인한 사용자가 특정 친구를 언팔로우합니다.")
    @DeleteMapping("/users/follow/{friendId}")
    public ResponseEntity<ApiResponse<String>> unfollow(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollow(authentication, friendId);
        return ResponseEntity.ok(ApiResponse.success("팔로우가 취소되었습니다."));
    }


    @PatchMapping(value = "/profile/edit", consumes = "application/json", produces = "application/json")
    @Operation(
            summary = "마이 프로필 수정(인증된 사용자)",
            description = "SecurityContext 의 인증 객체에서 사용자 정보를 가져와 프로필을 부분 수정합니다."
    )
    public ResponseEntity<UserUpdateDTO> editProfile(
            @RequestBody UserUpdateDTO dto
    ) {
        UserUpdateDTO response = userService.updateUserProfile(dto);
        return ResponseEntity.ok(response);
    }



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


    @GetMapping("profile/find/test")
    @Operation(
            summary = "친구리스트에서 이름(FirstName과 LastName) 을 통한 검색(테스트 버전) ",
            description = "단순 이름을 통해 사용자를 검색하여 리스트를 나열해 줍니다."
    )
    public ResponseEntity<List<UserUpdateDTO>> findProfileTest(
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName
    ) {
        log.info("일단 확인");
        List<UserUpdateDTO> response = userService.findUserByName(firstName, lastName);
        return ResponseEntity.ok(response);
    }

    @GetMapping("profile/find")
    @Operation(
            summary = "친구리스트에서 이름(FirstName과 LastName)을 통한 검색",
            description = "단순 이름을 통해 사용자를 검색하여 리스트를 나열. (현재 로그인 사용자 제외)"
    )
    public ResponseEntity<List<UserUpdateDTO>> findProfile(
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName
    ) {
        Long currentUserId = getCurrentUserIdOrNull();
        if (currentUserId == null) {
            // 로그인 필수라면 예외로 처리
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        List<UserUpdateDTO> response = userService.findUserByNameExcludingSelf(firstName, lastName, currentUserId);
        return ResponseEntity.ok(response);
    }

    private Long getCurrentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
         if (principal instanceof CustomUserDetails cud) {
             return cud.getId();
         }
        return null;
    }

}


