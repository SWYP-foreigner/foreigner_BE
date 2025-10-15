package core.domain.user.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record UserSearchRequest(
        String email,
        String name,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startDate, // 검색 시작일
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate endDate    // 검색 종료일
) {}
