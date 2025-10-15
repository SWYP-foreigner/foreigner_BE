package core.global.controller;

import core.domain.user.dto.*;
import core.domain.user.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/users") // 이 컨트롤러는 /admin/users 경로를 담당
@RequiredArgsConstructor
public class UserAdminViewController {

    private final UserAdminService userAdminService;
    private static final int DEFAULT_PAGE_SIZE = 5;

    /**
     * 관리자 홈페이지(메인 메뉴)를 보여줍니다.
     */
    @GetMapping("/home")
    public String adminHomePage() {
        // 모델에 데이터를 추가할 필요 없이 페이지만 반환
        return "admin/home";
    }

    // GET /admin/users 요청을 처리
    @GetMapping
    public String userListPage(
            @ModelAttribute UserSearchRequest request,
            @PageableDefault(size = 10, sort = "createdAt,desc") Pageable pageable,
            Model model // 뷰(HTML)에 데이터를 전달할 객체
    ) {
        // 1. 서비스 로직을 호출해 유저 목록(Page 객체)을 가져옵니다.
        Page<UserListResponse> userPage = userAdminService.searchUsers(request, pageable);

        // 2. Model 객체에 뷰에서 사용할 데이터를 담습니다.
        model.addAttribute("userPage", userPage);
        model.addAttribute("searchRequest", request); // 검색 조건 유지를 위해 추가

        // 3. 뷰의 논리적 이름을 반환합니다.
        // "admin/user-list" -> templates/admin/user-list.html 파일을 찾아 렌더링함
        return "admin/user-list";
    }

    @GetMapping("/{userId}")
    public String userDetailPage(@PathVariable Long userId, Model model,
                                 @RequestParam(defaultValue = "0") int followPage,
                                 @RequestParam(defaultValue = "0") int blockPage,
                                 @RequestParam(defaultValue = "0") int chatPage) {

        // 각 목록에 대한 Pageable 객체 생성
        Pageable followPageable = PageRequest.of(followPage, DEFAULT_PAGE_SIZE);
        Pageable blockPageable = PageRequest.of(blockPage, DEFAULT_PAGE_SIZE);
        Pageable chatPageable = PageRequest.of(chatPage, DEFAULT_PAGE_SIZE);

        // 서비스 호출
        UserBasicInfoDto userInfo = userAdminService.getUserBasicInfo(userId);
        Page<FollowingInfoDto> followings = userAdminService.getFollowingsForUser(userId, followPageable);
        Page<BlockedUserInfoDto> blockedUsers = userAdminService.getBlockedUsersForUser(userId, blockPageable);
        Page<ChatRoomInfoDto> chatRooms = userAdminService.getChatRoomsForUser(userId, chatPageable);

        // 모델에 Page 객체들 추가
        model.addAttribute("user", userInfo);
        model.addAttribute("followings", followings);
        model.addAttribute("blockedUsers", blockedUsers);
        model.addAttribute("chatRooms", chatRooms);

        return "admin/user-detail";
    }
}
