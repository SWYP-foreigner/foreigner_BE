package core.global.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminMonitoringController {

    @GetMapping("/monitoring")
    public String monitoringPage() {
        return "admin/monitoring";
    }
}
