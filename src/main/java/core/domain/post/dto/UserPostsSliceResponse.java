package core.domain.post.dto;

import java.util.List;

public record UserPostsSliceResponse(
        List<UserPostResponse> items,
        Long nextCursor,
        boolean hasNext
) {

}
