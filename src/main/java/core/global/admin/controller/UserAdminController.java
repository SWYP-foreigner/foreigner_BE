package core.global.admin.controller;

import core.domain.user.dto.UserBasicInfoDto;
import core.domain.user.dto.UserListResponse;
import core.domain.user.dto.UserSearchRequest;
import core.domain.user.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping("/{userId}/basic")
    public ResponseEntity<UserBasicInfoDto> getUserBasicInfo(@PathVariable Long userId) {
        UserBasicInfoDto userInfo = userAdminService.getUserBasicInfo(userId);
        return ResponseEntity.ok(userInfo);
    }

    @GetMapping
    public ResponseEntity<Page<UserListResponse>> searchUsers(
            @ModelAttribute UserSearchRequest request,
            @PageableDefault(size = 10, sort = "createdAt,desc") Pageable pageable
    ) {
        Page<UserListResponse> results = userAdminService.searchUsers(request, pageable);
        return ResponseEntity.ok(results);
    }
}
