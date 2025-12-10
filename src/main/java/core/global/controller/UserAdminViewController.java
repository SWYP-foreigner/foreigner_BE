package core.global.controller;

import core.domain.chat.dto.RecentMessageDto;
import core.domain.comment.dto.RecentCommentDto;
import core.domain.post.dto.admin.RecentPostDto;
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
                                 @RequestParam(defaultValue = "0") int chatPage,
                                 @RequestParam(defaultValue = "0") int postPage,
                                 @RequestParam(defaultValue = "0") int commentPage,
                                 @RequestParam(defaultValue = "0") int messagePage
    ) {

        Pageable followPageable = PageRequest.of(followPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());
        Pageable blockPageable = PageRequest.of(blockPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());
        Pageable chatPageable = PageRequest.of(chatPage, DEFAULT_PAGE_SIZE, Sort.by("id").ascending());

        Pageable postPageable = PageRequest.of(postPage, 5, Sort.by("id").descending());
        Pageable commentPageable = PageRequest.of(commentPage, 5, Sort.by("id").descending());
        Pageable messagePageable = PageRequest.of(messagePage, 5, Sort.by("sentAt").descending());

        UserBasicInfoDto userInfo = userAdminService.getUserBasicInfo(userId);
        Page<FollowingInfoDto> followings = userAdminService.getFollowingsForUser(userId, followPageable);
        Page<BlockedUserInfoDto> blockedUsers = userAdminService.getBlockedUsersForUser(userId, blockPageable);
        Page<ChatRoomInfoDto> chatRooms = userAdminService.getChatRoomsForUser(userId, chatPageable);

        Page<RecentPostDto> posts = userAdminService.getRecentPostsForUser(userId, postPageable);
        Page<RecentCommentDto> comments = userAdminService.getRecentCommentsForUser(userId, commentPageable);
        Page<RecentMessageDto> messages = userAdminService.getRecentMessagesForUser(userId, messagePageable);

        model.addAttribute("user", userInfo);
        model.addAttribute("followings", followings);
        model.addAttribute("blockedUsers", blockedUsers);
        model.addAttribute("chatRooms", chatRooms);
        model.addAttribute("posts", posts);
        model.addAttribute("comments", comments);
        model.addAttribute("messages", messages);

        return "admin/user-detail";
    }

    @PostMapping("/{userId}/posts/{postId}/delete")
    public String deletePost(@PathVariable Long userId, @PathVariable Long postId) {
        userAdminService.deletePost(postId);
        return "redirect:/admin/users/" + userId + "?postPage=0";
    }

    @PostMapping("/{userId}/comments/{commentId}/delete")
    public String deleteComment(@PathVariable Long userId, @PathVariable Long commentId) {
        userAdminService.deleteComment(commentId);
        return "redirect:/admin/users/" + userId + "?commentPage=0";
    }

    @PostMapping("/{userId}/messages/{messageId}/delete")
    public String deleteMessage(@PathVariable Long userId, @PathVariable Long messageId) {
        userAdminService.deleteMessage(messageId);
        return "redirect:/admin/users/" + userId + "?messagePage=0";
    }

    @PostMapping("/{userId}/chat-rooms/{chatRoomId}/delete")
    public String deleteChatRoom(@PathVariable Long userId, @PathVariable Long chatRoomId) {
        userAdminService.deleteChatRoom(chatRoomId);
        return "redirect:/admin/users/" + userId + "?chatPage=0";
    }

    @PostMapping("/{userId}/delete")
    public String deleteUser(@PathVariable Long userId) {
        userAdminService.hardDeleteUser(userId);
        return "redirect:/admin/users";
    }
}
