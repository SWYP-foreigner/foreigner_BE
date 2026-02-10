package core.domain.admin.controller;

import core.domain.comment.dto.CommentListResponse;
import core.domain.comment.dto.CommentSearchRequest;
import core.domain.admin.service.CommentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/comments")
@RequiredArgsConstructor
public class CommentAdminViewController {

    private final CommentAdminService commentAdminService;

    @GetMapping
    public String commentListPage(
            @ModelAttribute CommentSearchRequest request,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<CommentListResponse> commentPage = commentAdminService.searchComments(request, pageable);
        model.addAttribute("commentPage", commentPage);
        model.addAttribute("searchRequest", request);
        return "admin/comment-list";
    }

    @PostMapping("/{commentId}/delete")
    public String deleteComment(@PathVariable Long commentId) {
        commentAdminService.deleteComment(commentId);
        return "redirect:/admin/comments";
    }

    @PostMapping("/{commentId}/delete-and-ban")
    public String deleteCommentAndBanUser(@PathVariable Long commentId) {
        commentAdminService.deleteCommentAndBanUser(commentId);
        return "redirect:/admin/comments";
    }
}
