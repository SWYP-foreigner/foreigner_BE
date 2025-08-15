package core.domain.comment.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentWriteRequest(
        Long parentId,
        Boolean anonymous,
        @NotBlank @Size(max = 2000)
        String comment
) {
}
