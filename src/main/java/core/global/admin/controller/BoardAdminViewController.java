package core.global.admin.controller;

import core.domain.board.dto.BoardCreateRequest;
import core.domain.board.dto.BoardDto;
import core.global.exception.BusinessException;
import core.global.service.BoardAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/boards")
@RequiredArgsConstructor
public class BoardAdminViewController {

    private final BoardAdminService boardAdminService;

    @GetMapping
    public String boardListPage(Model model) {
        List<BoardDto> boards = boardAdminService.getAllBoards();
        model.addAttribute("boards", boards);
        if (!model.containsAttribute("boardCreateRequest")) {
            model.addAttribute("boardCreateRequest", new BoardCreateRequest(""));
        }
        return "admin/board-list";
    }

    @PostMapping
    public String createBoard(
            @Valid @ModelAttribute("boardCreateRequest") BoardCreateRequest request, // 모델 attribute 이름 명시
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.boardCreateRequest", bindingResult);
            redirectAttributes.addFlashAttribute("boardCreateRequest", request);
            return "redirect:/admin/boards";
        }

        try {
            boardAdminService.createBoard(request);
            redirectAttributes.addFlashAttribute("successMessage", "새 카테고리가 추가되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            redirectAttributes.addFlashAttribute("boardCreateRequest", request);
        }

        return "redirect:/admin/boards";
    }

    @PostMapping("/{boardId}/delete")
    public String deleteBoard(@PathVariable Long boardId, RedirectAttributes redirectAttributes) {
        try {
            boardAdminService.deleteBoardAndAssociatedPosts(boardId);
            redirectAttributes.addFlashAttribute("successMessage", "카테고리와 관련 게시글이 모두 삭제되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/boards";
    }
}