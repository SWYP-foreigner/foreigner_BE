package core.domain.admin.controller;

import core.domain.admin.dto.AdminMetricsDto;
import core.domain.admin.service.AdminMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@Controller
@RequestMapping("/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsViewController {

    private final AdminMetricsService metricsService;

    @GetMapping
    public String metricsDashboard(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate joinStartDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate joinEndDate,
            @RequestParam(defaultValue = "30") int validJoinDays,
            @RequestParam(defaultValue = "7") int validActiveDays,
            Model model
    ) {
        if (joinStartDate == null) joinStartDate = LocalDate.now().minusDays(7);
        if (joinEndDate == null) joinEndDate = LocalDate.now();

        AdminMetricsDto metrics = metricsService.getDashboardMetrics(joinStartDate, joinEndDate, validJoinDays, validActiveDays);

        model.addAttribute("metrics", metrics);
        model.addAttribute("joinStartDate", joinStartDate);
        model.addAttribute("joinEndDate", joinEndDate);
        model.addAttribute("validJoinDays", validJoinDays);
        model.addAttribute("validActiveDays", validActiveDays);

        return "admin/metrics";
    }
}
