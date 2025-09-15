package core.domain.user.controller;

import core.domain.user.service.FollowService;
import core.domain.user.service.UserService;
import core.global.dto.ApiResponse;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Main Home (친구 둘러보기)", description = "친구 찾기, 팔로우, 채팅 관련 API")
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class MainHomeController {
    private final UserService userService;
    private final FollowService followService;


    @Operation(summary = "팔로우 요청 보내기", description = "마음에 드는 친구에게 팔로우 요청을 전송합니다.")
    @PostMapping("/follow/{userId}")
    public ResponseEntity<ApiResponse<String>> followUser(
            Authentication authentication, @PathVariable Long userId) {
        followService.follow(authentication, userId);
        return ResponseEntity.ok(ApiResponse.success("팔로우 요청이 전송되었습니다."));
    }

}