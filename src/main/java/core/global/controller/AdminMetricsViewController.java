package core.global.controller;

import core.global.dto.AdminMetricsDto;
import core.global.service.AdminMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsViewController {

    private final AdminMetricsService metricsService;

    @GetMapping
    public String metricsDashboard(
            @RequestParam(defaultValue = "7") int signupDays,
            @RequestParam(defaultValue = "30") int validJoinDays,
            @RequestParam(defaultValue = "7") int validActiveDays,
            Model model
    ) {
        AdminMetricsDto metrics = metricsService.getDashboardMetrics(signupDays, validJoinDays, validActiveDays);

        model.addAttribute("metrics", metrics);
        model.addAttribute("signupDays", signupDays);
        model.addAttribute("validJoinDays", validJoinDays);
        model.addAttribute("validActiveDays", validActiveDays);

        return "admin/metrics";
    }
}
