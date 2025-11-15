package core.domain.comment.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record CommentSearchRequest(
        String authorEmail,
        String authorName,
        String content,
        Boolean onlyReported,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate endDate
) {}
