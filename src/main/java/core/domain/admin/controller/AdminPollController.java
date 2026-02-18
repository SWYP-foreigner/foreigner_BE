package core.domain.admin.controller;

import core.domain.poll.dto.PollItem;
import core.domain.poll.service.PollService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
    public String listQuizzes(
            Model model,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<PollItem> quizzes = pollService.getAllQuizzes(pageable);

        model.addAttribute("quizzes", quizzes);
        return "admin/quiz/list";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        return "admin/quiz/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        // 기존 데이터를 PollItem 형태로 가져와 폼에 채움
        model.addAttribute("quiz", pollService.getQuizDetail(id));
        return "admin/quiz/form";
    }
}