package core.domain.post.dto.admin;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record PostSearchForAdminRequest(
        String authorEmail,
        String authorName,
        String content,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate endDate
) {}
