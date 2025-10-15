package core.global.controller;

import core.domain.user.dto.*;
import core.domain.user.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserAdminViewController {

    private final UserAdminService userAdminService;
    private static final int DEFAULT_PAGE_SIZE = 5;

    /**
     * 관리자 홈페이지(메인 메뉴)를 보여줍니다.
     */
    @GetMapping("/home")
    public String adminHomePage() {
        return "admin/home";
    }

    @GetMapping
    public String userListPage(
            @ModelAttribute UserSearchRequest request,
            @PageableDefault(size = 10, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {

        Page<UserListResponse> userPage = userAdminService.searchUsers(request, pageable);

        model.addAttribute("userPage", userPage);
        model.addAttribute("searchRequest", request);

        return "admin/user-list";
    }

    @GetMapping("/{userId}")
    public String userDetailPage(@PathVariable Long userId, Model model,
                                 @RequestParam(defaultValue = "0") int followPage,
                                 @RequestParam(defaultValue = "0") int blockPage,
                                 @RequestParam(defaultValue = "0") int chatPage) {

        Pageable followPageable = PageRequest.of(followPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());
        Pageable blockPageable = PageRequest.of(blockPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());
        Pageable chatPageable = PageRequest.of(chatPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());

        UserBasicInfoDto userInfo = userAdminService.getUserBasicInfo(userId);
        Page<FollowingInfoDto> followings = userAdminService.getFollowingsForUser(userId, followPageable);
        Page<BlockedUserInfoDto> blockedUsers = userAdminService.getBlockedUsersForUser(userId, blockPageable);
        Page<ChatRoomInfoDto> chatRooms = userAdminService.getChatRoomsForUser(userId, chatPageable);

        model.addAttribute("user", userInfo);
        model.addAttribute("followings", followings);
        model.addAttribute("blockedUsers", blockedUsers);
        model.addAttribute("chatRooms", chatRooms);

        return "admin/user-detail";
    }

    @PostMapping("/{userId}/delete")
    public String deleteUser(@PathVariable Long userId) {
        userAdminService.hardDeleteUser(userId);
        return "redirect:/admin/users";
    }
}
