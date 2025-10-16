package core.global.controller;

import core.global.dto.AdminMetricsDto;
import core.global.service.AdminMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsViewController {

    private final AdminMetricsService metricsService;

    @GetMapping
    public String metricsDashboard(Model model) {
        AdminMetricsDto metrics = metricsService.getDashboardMetrics();
        model.addAttribute("metrics", metrics);
        return "admin/metrics";
    }
}
