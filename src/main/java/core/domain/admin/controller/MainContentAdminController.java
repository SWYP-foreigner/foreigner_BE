package core.domain.admin.controller;

import core.domain.admin.dto.MainContentListResponse;
import core.domain.admin.dto.MainContentSearchRequest;
import core.domain.admin.service.MainContentAdminService;
import core.global.enums.KNewsContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/main-contents")
@RequiredArgsConstructor
public class MainContentAdminController {

    private final MainContentAdminService mainContentAdminService;

    @GetMapping
    public String mainContentListPage(
            @ModelAttribute MainContentSearchRequest request,
            @PageableDefault(size = 10, sort = {"createdAt"}, direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<MainContentListResponse> contentPage = mainContentAdminService.searchContents(request, pageable);

        model.addAttribute("contentPage", contentPage);
        model.addAttribute("searchRequest", request);
        model.addAttribute("contentTypes", KNewsContentType.values()); // 검색 필터용 Enum 값

        return "admin/main-content-list";
    }

    @PostMapping("/{id}/delete")
    public String deleteMainContent(@PathVariable Long id) {
        mainContentAdminService.deleteMainContent(id);
        return "redirect:/admin/main-contents";
    }
}
