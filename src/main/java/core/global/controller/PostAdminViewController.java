package core.global.controller;

import core.domain.post.dto.PostListResponse;
import core.domain.post.dto.PostSearchRequest;
import core.global.service.PostAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/posts")
@RequiredArgsConstructor
public class PostAdminViewController {

    private final PostAdminService postAdminService;

    @GetMapping
    public String postListPage(
            @ModelAttribute PostSearchRequest request,
            @PageableDefault(size = 10, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<PostListResponse> postPage = postAdminService.searchPosts(request, pageable);
        model.addAttribute("postPage", postPage);
        model.addAttribute("searchRequest", request);
        return "admin/post-list";
    }
}
