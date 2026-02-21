package core.domain.admin.controller;

import core.domain.poll.service.PollService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/quizzes")
public class AdminPollController {
    private final PollService pollService;

    public AdminPollController(PollService pollService) {
        this.pollService = pollService;
    }

    @GetMapping
    public String listQuizzes(Model model) {
        model.addAttribute("quizzes", pollService.getAllQuizzes());
        return "admin/quiz/list";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        // 새 작성이므로 빈 객체나 null 전달
        model.addAttribute("quiz", null);
        return "admin/quiz/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        // 기존 데이터를 PollItem 형태로 가져와 폼에 채움
        model.addAttribute("quiz", pollService.getQuizDetail(id));
        return "admin/quiz/form";
    }
}