package core.domain.admin.controller;

import core.domain.admin.dto.MainContentDetailDto;
import core.domain.admin.dto.MainContentListResponse;
import core.domain.admin.dto.MainContentSearchRequest;
import core.domain.admin.service.MainContentAdminService;
import core.global.enums.KNewsContentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

@Slf4j
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

    @GetMapping("/{id}")
    public String mainContentDetailPage(@PathVariable Long id, Model model) {
        MainContentDetailDto content = mainContentAdminService.getDetail(id);

        model.addAttribute("content", content);
        model.addAttribute("kNewsTypes", KNewsContentType.values());
        return "admin/main-content-detail";
    }

    @PostMapping("/{id}/update")
    public String updateMainContent(
            @PathVariable Long id,
            @RequestParam("title") String title,
            @RequestParam("kNewsType") String kNewsTypeStr,
            @RequestParam("content") String content,
            @RequestPart(value = "mainThumbnailFile", required = false) MultipartFile mainThumbnailFile,
            @RequestPart(value = "popularThumbnailFile", required = false) MultipartFile popularThumbnailFile,
            @RequestPart(value = "contentImages", required = false) List<MultipartFile> contentImages,
            RedirectAttributes redirectAttributes
    ) {
        try {
            mainContentAdminService.updateMainContent(id, title, kNewsTypeStr, content,
                    mainThumbnailFile, popularThumbnailFile, contentImages);

            redirectAttributes.addFlashAttribute("successMessage", "컨텐츠가 성공적으로 수정되었습니다.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "수정 중 오류 발생: " + e.getMessage());
        }
        return "redirect:/admin/main-contents/" + id;
    }

    @GetMapping("/proxy-image")
    @ResponseBody
    public ResponseEntity<byte[]> proxyImage(@RequestParam("url") String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            URLConnection connection = url.openConnection();

            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            try (InputStream inputStream = connection.getInputStream()) {
                byte[] imageBytes = inputStream.readAllBytes();

                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(imageBytes);
            }
        } catch (IOException e) {
            log.error("Proxy Image Load Failed: {}", imageUrl, e);
            return ResponseEntity.notFound().build();
        }
    }
}
