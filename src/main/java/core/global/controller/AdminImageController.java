package core.global.controller;

import core.global.entity.image.dto.SuspiciousImageResponse;
import core.global.entity.image.entity.Image;
import core.global.service.AdminImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/admin/images")
@RequiredArgsConstructor
public class AdminImageController {

    private final AdminImageService adminImageService;

    @GetMapping("/reviews")
    public String reviewPage(Model model) {
        List<SuspiciousImageResponse> images = adminImageService.getSuspiciousImages();
        model.addAttribute("images", images);
        return "admin/image-reviews";
    }

    @PostMapping("/{id}/pass")
    public String approveImage(@PathVariable Long id) {
        adminImageService.approveImage(id);
        return "redirect:/admin/images/reviews";
    }

    @PostMapping("/{id}/delete")
    public String deleteImage(@PathVariable Long id) {
        adminImageService.deleteImage(id);
        return "redirect:/admin/images/reviews";
    }
}
