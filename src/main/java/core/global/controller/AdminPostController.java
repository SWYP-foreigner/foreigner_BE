package core.global.controller;

import core.domain.post.service.PostService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.config.CustomUserDetails;
import core.global.enums.community.BoardCategory;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import static core.global.enums.errorcode.UserErrorCode.USER_NOT_FOUND;

@Slf4j
@Controller
@RequestMapping("/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;
    private final UserRepository userRepository;

    /**
     * 어드민용 자체 포스트 등록 폼 페이지를 반환합니다.
     */
    @GetMapping("/new")
    public String showCreatePostForm() {
        // "templates/admin/admin-post-form.html"을 반환
        return "admin/admin-post-form";
    }

    /**
     * 어드민 페이지에서 직접 포스트를 등록합니다.
     */
    @PostMapping("/custom")
    public String createCustomPost(
            @RequestParam("boardCategory") String boardCategory,
            @RequestParam("content") String content,
            @RequestParam("images") List<MultipartFile> images,
            @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirectAttributes
    ) {

        try {
            User adminUser = userRepository.findById(principal.getUserId())
                    .orElseThrow(() -> new BusinessException(USER_NOT_FOUND));

            BoardCategory category = BoardCategory.valueOf(boardCategory.toUpperCase());

            postService.createAdminPost(content, category, images, adminUser);

            redirectAttributes.addFlashAttribute("successMessage", "포스트가 성공적으로 발행되었습니다.");
            return "redirect:/admin/crawled-data";

        } catch (IllegalArgumentException e) {
            log.warn("Invalid BoardCategory provided: {}", boardCategory, e);
            redirectAttributes.addFlashAttribute("errorMessage", "유효하지 않은 게시판 카테고리입니다.");
            return "redirect:/admin/posts/new";
        } catch (Exception e) {
            log.error("Failed to create custom post", e);
            redirectAttributes.addFlashAttribute("errorMessage", "포스트 등록 실패: " + e.getMessage());
            return "redirect:/admin/posts/new";
        }
    }
}
