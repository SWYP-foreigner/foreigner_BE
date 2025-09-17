package core.domain.user.controller;

import core.domain.chat.service.TranslationService;
import core.domain.user.dto.FollowDTO;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.dto.UserLanguageDTO;
import core.global.enums.FollowStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
public class MyPageController {


    private final UserService userService;
    private final FollowService followService;
    private final TranslationService translationService;

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

        return ResponseEntity.ok().body(list);
    }



    @Operation(summary = "Received/Sent 수 조회", description = "PENDING 상태의 보낸/받은 팔로우 요청 수를 조회합니다.")
    @GetMapping("/follows/pending/count")
    public ResponseEntity<Map<String, Long>> getPendingFollowsCount(Authentication authentication) {
        Map<String, Long> counts = followService.getPendingFollowCounts(authentication);
        return ResponseEntity.ok(counts);
    }



    @Operation(
            summary = "로그인 한 사용자의 맞팔 목록(ACCEPTED)",
            description = "ACCEPTED 상태의 팔로우 관계만 가져옵니다."
    )
    @GetMapping("/follows/accepted")
    public ResponseEntity<List<FollowDTO>> getAcceptedFollows(
            Authentication authentication
    ) {
        List<FollowDTO> list = followService.getMyAcceptedFollows(authentication);
        return ResponseEntity.ok(list);
    }


    @Operation(summary = "팔로우 요청 수락", description = "나에게 들어온 팔로우 요청을 수락합니다.")
    @PatchMapping("/accept-follow/{fromUserId}")
    public ResponseEntity<Void> acceptFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID") @PathVariable Long fromUserId) {

        followService.acceptFollow(authentication, fromUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "팔로우 요청 거절 (decline) ", description = "나에게 들어온 팔로우 요청을 거절합니다. ")
    @DeleteMapping("/decline-follow/{fromUserId}")
    public ResponseEntity<Void> declineFollowRequest(
            Authentication authentication,
            @Parameter(description = "팔로우를 요청한 사용자(팔로워)의 ID")
            @PathVariable Long fromUserId) {

        followService.declineFollow(authentication, fromUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "친구가 되기 전에 PENDING 상태 팔로우 요청 취소",
            description = "팔로우 요청을 취소합니다.")
    @DeleteMapping("/users/follow/{friendId}")
    public ResponseEntity<Void> unfollowPending(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollow(authentication, friendId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "친구가 된 후 ACCEPTED 상태 친구 관계 해제",
            description = "친구 관계를 해제합니다.")
    @DeleteMapping("/users/follow/accepted/{friendId}")
    public ResponseEntity<Void> unfollowAccepted(
            Authentication authentication,
            @PathVariable("friendId") Long friendId) {

        followService.unfollowAccepted(authentication, friendId);
        return ResponseEntity.ok().build();
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

//    @GetMapping("/profile/find")
//    @Operation(
//            summary = "친구리스트에서 이름(FirstName과 LastName)을 통한 검색",
//            description = "단순 이름을 통해 사용자를 검색하여 리스트를 나열. (현재 로그인 사용자 제외)"
//    )
//    public ResponseEntity<List<UserSearchDTO>> findProfile(
//            @RequestParam(required = false) String firstName,
//            @RequestParam(required = false) String lastName
//    ) {
//        // 서비스 메서드에 currentUserDetails 객체를 전달
//        List<UserSearchDTO> response = userService.findUserByNameExcludingSelf(firstName, lastName);
//        return ResponseEntity.ok(response);
//    }

    @PutMapping("/user/language")
    @Operation(summary = "사용자 언어 설정.", description = "인증된 사용자의 기본 채팅 언어를 저장합니다.")
    public ResponseEntity<Void> updateUserLanguage(
            Authentication auth,
            @RequestBody UserLanguageDTO dto) {

        translationService.saveUserLanguage(auth, dto.getLanguage());

        return ResponseEntity.ok().build();
    }



}


