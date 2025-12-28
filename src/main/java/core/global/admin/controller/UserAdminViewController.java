package core.global.admin.controller;

import core.domain.chat.dto.RecentMessageDto;
import core.domain.chat.entity.ChatRoom;
import core.domain.comment.dto.RecentCommentDto;
import core.domain.post.dto.admin.RecentPostDto;
import core.domain.user.dto.*;
import core.domain.user.entity.User;
import core.domain.user.service.UserAdminService;
import core.domain.aiuser.entity.AiPersona;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;

@Slf4j
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

        boolean isAi = userAdminService.isAiUser(userId);
        model.addAttribute("isAi", isAi);

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

    @GetMapping("/create-ai")
    public String createAiUserPage(Model model) {
        model.addAttribute("setupRequest", new UserSetupRequest(null, null, null, null, null, null, null, null, null, null, null));

        addAiFormAttributes(model);

        return "admin/user-create-ai";
    }

    @PostMapping("/create-ai")
    public String createAiUser(
            @ModelAttribute @Valid UserSetupRequest request,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "profileFile", required = false) MultipartFile profileFile,
            @RequestParam(value = "instruction") String instruction,
            @RequestParam(value = "backgroundInfo", required = false) String backgroundInfo
    ) {
        userAdminService.createAiUser(request, password, profileFile, instruction, backgroundInfo);
        return "redirect:/admin/users";
    }

    @GetMapping("/ai/invite")
    public String inviteAiPage(Model model) {
        List<User> aiUsers = userAdminService.getAiUsers();
        List<ChatRoom> chatRooms = userAdminService.getGroupChatRooms();

        model.addAttribute("aiUsers", aiUsers);
        model.addAttribute("chatRooms", chatRooms);

        return "admin/ai-invite";
    }

    @PostMapping("/ai/invite")
    public String inviteAiProcess(
            @RequestParam("userId") Long userId,
            @RequestParam("chatRoomId") Long chatRoomId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            userAdminService.addAiToChatRoom(userId, chatRoomId);
            redirectAttributes.addFlashAttribute("message", "성공적으로 AI를 채팅방에 초대했습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "초대 실패: " + e.getMessage());
        }

        return "redirect:/admin/users/ai/invite";
    }

    @GetMapping("/{userId}/edit-ai")
    public String editAiUserPage(@PathVariable Long userId, Model model) {
        UserSetupRequest setupRequest = userAdminService.getAiUserForEdit(userId);
        AiPersona persona = userAdminService.getAiPersona(userId);

        model.addAttribute("setupRequest", setupRequest);
        model.addAttribute("userId", userId);

        model.addAttribute("instruction", persona != null ? persona.getInstruction() : "");
        model.addAttribute("backgroundInfo", persona != null ? persona.getBackgroundInfo() : "");

        addAiFormAttributes(model);

        return "admin/user-edit-ai";
    }

    @PostMapping("/{userId}/edit-ai")
    public String editAiUserProcess(
            @PathVariable Long userId,
            @ModelAttribute @Valid UserSetupRequest request,
            BindingResult bindingResult,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "profileFile", required = false) MultipartFile profileFile,
            @RequestParam(value = "instruction") String instruction,
            @RequestParam(value = "backgroundInfo", required = false) String backgroundInfo,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            addAiFormAttributes(model);
            model.addAttribute("userId", userId);
            model.addAttribute("instruction", instruction);
            model.addAttribute("backgroundInfo", backgroundInfo);

            String errorMessage = bindingResult.getAllErrors().get(0).getDefaultMessage();
            model.addAttribute("errorMessage", "입력값 오류: " + errorMessage);

            return "admin/user-edit-ai";
        }

        try {
            userAdminService.updateAiUser(userId, request, password, profileFile, instruction, backgroundInfo);
            redirectAttributes.addFlashAttribute("successMessage", "AI 유저 및 페르소나 정보가 수정되었습니다.");
        } catch (Exception e) {
            log.error("AI 유저 수정 중 오류 발생 - UserId: {}", userId, e);
            redirectAttributes.addFlashAttribute("errorMessage", "수정 실패: " + e.getMessage());
        }

        return "redirect:/admin/users/" + userId;
    }

    private void addAiFormAttributes(Model model) {
        List<String> purposes = Arrays.asList("Study", "Work", "Marriage", "Travel", "Business", "Family");
        model.addAttribute("purposes", purposes);

        List<String> hobbies = Arrays.asList(
                "Music", "Movies", "Reading", "Anime", "Gaming",
                "Drinking", "Exploring Cafes", "Traveling", "Board Games",
                "Shopping", "Beauty", "Doing Nothing",
                "Yoga", "Running", "Fitness", "Camping", "Dancing", "Hiking",
                "Exhibition", "Singing", "Cooking", "Pets", "Career", "Photography",
                "K-Pop Lover", "K-Drama Lover", "K-Food Lover"
        );
        model.addAttribute("hobbies", hobbies);

        List<String> profileImages = Arrays.asList(
                "https://kr.object.ncloudstorage.com/foreigner-bucket/default/character_01.svg",
                "https://kr.object.ncloudstorage.com/foreigner-bucket/default/character_02.svg",
                "https://kr.object.ncloudstorage.com/foreigner-bucket/default/character_03.svg"
        );
        model.addAttribute("profileImages", profileImages);
    }

    @GetMapping("/ai")
    public String aiUserListPage(Model model) {
        List<User> aiUsers = userAdminService.getAiUsers();

        model.addAttribute("aiUsers", aiUsers);
        return "admin/user-list-ai";
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public String healthCheckPage() {
        return "admin/health-check"; // templates/admin/health-check.html 파일
    }
}
