package core.global.admin.controller;

import core.domain.notification.service.PushNotificationService;
import core.domain.user.repository.UserRepository;
import core.global.dto.TargetPushRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/push")
@RequiredArgsConstructor
public class AdminPushController {

    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;

    @GetMapping
    public String pushPage(Model model) {
        List<String> countries = userRepository.findDistinctCountries();

        model.addAttribute("countries", countries);
        model.addAttribute("pushRequest", new TargetPushRequest(null, null, null));

        return "admin/push-send";
    }

    @PostMapping("/send")
    public String sendPush(
            @ModelAttribute TargetPushRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (request.targetCountry() == null || request.targetCountry().isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "국가를 선택해주세요.");
            return "redirect:/admin/push";
        }
        if (request.body() == null || request.body().isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "내용을 입력해주세요.");
            return "redirect:/admin/push";
        }

        try {
            Long adminId = 1L;
            pushNotificationService.sendPushToCountry(
                    request.targetCountry(),
                    request.title(),
                    request.body(),
                    adminId
            );

            redirectAttributes.addFlashAttribute("successMessage",
                    "'" + request.targetCountry() + "' 국가 유저들에게 알림 발송을 시작했습니다.");
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "발송 실패: " + e.getMessage());
        }

        return "redirect:/admin/push";
    }
}
