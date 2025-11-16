package core.global.controller;

import core.domain.post.dto.admin.PostListForAdminResponse;
import core.domain.post.dto.admin.PostSearchForAdminRequest;
import core.global.service.PostAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/posts")
@RequiredArgsConstructor
public class PostAdminViewController {

    private final PostAdminService postAdminService;

    @GetMapping
    public String postListPage(
            @ModelAttribute PostSearchForAdminRequest request,
            @PageableDefault(size = 10, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<PostListForAdminResponse> postPage = postAdminService.searchPosts(request, pageable);
        model.addAttribute("postPage", postPage);
        model.addAttribute("searchRequest", request);
        return "admin/post-list";
    }

    @PostMapping("/{postId}/delete")
    public String deletePost(@PathVariable Long postId) {
        postAdminService.deletePost(postId);
        return "redirect:/admin/posts";
    }

    @PostMapping("/{postId}/delete-and-ban")
    public String deletePostAndBanUser(@PathVariable Long postId) {
        postAdminService.deletePostAndBanUser(postId);
        return "redirect:/admin/posts";
    }
}
