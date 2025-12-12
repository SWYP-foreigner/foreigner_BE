package core.global.controller;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.crawling.CrawledDataDto;
import core.domain.post.entity.CrawledData;
import core.global.exception.BusinessException;
import core.global.service.CrawledDataAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin/crawled-data")
@RequiredArgsConstructor
public class CrawledDataAdminController {

    private final CrawledDataAdminService crawledDataAdminService;
    private final BoardRepository boardRepository;

    @GetMapping
    public String crawledDataListPage(
            @PageableDefault(size = 5, sort = "crawledAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<CrawledDataDto> crawledDataPage = crawledDataAdminService.getPendingCrawledData(pageable);
        List<Board> boards = boardRepository.findAll();

        model.addAttribute("crawledDataPage", crawledDataPage);
        model.addAttribute("boards", boards);
        return "admin/crawled-data-list";
    }

    @GetMapping("/{id}")
    public String crawledDataDetailPage(@PathVariable Long id, Model model) {
        CrawledData crawledData = crawledDataAdminService.getCrawledDataById(id);
        List<Board> boards = boardRepository.findAll();

        model.addAttribute("crawledData", crawledData);
        model.addAttribute("boards", boards);

        return "admin/crawled-data-detail";
    }

    @PostMapping("/{id}/approve")
    public String approveAndPost(
            @PathVariable Long id,
            @RequestParam Long boardId,
            @RequestParam String content,
            @RequestParam(value = "selectedImageUrls", required = false) List<String> selectedImageUrls,
            RedirectAttributes redirectAttributes
    ) {
        try {
            crawledDataAdminService.approveAndPost(id, boardId, content, selectedImageUrls);
            redirectAttributes.addFlashAttribute("successMessage", "데이터가 게시물로 성공적으로 발행되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/crawled-data";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        crawledDataAdminService.rejectCrawledData(id);
        redirectAttributes.addFlashAttribute("successMessage", "데이터가 거절 처리되었습니다.");
        return "redirect:/admin/crawled-data";
    }

    @PostMapping("/delete-old")
    public String deleteOldCrawledData(
            @RequestParam("targetDate") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate targetDate,
            RedirectAttributes redirectAttributes
    ) {
        try {
            long deletedCount = crawledDataAdminService.deleteCrawledDataBefore(targetDate);
            redirectAttributes.addFlashAttribute("successMessage",
                    targetDate + " 00시 이전의 데이터 " + deletedCount + "건이 삭제되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "삭제 중 오류 발생: " + e.getMessage());
        }
        return "redirect:/admin/crawled-data";
    }
}
