package core.domain.board.dto;

import jakarta.validation.constraints.NotBlank;

public record BoardCreateRequest(
        @NotBlank(message = "카테고리 이름을 입력해주세요.")
        String categoryName
) {}