package core.domain.board.service;

import core.domain.board.dto.CategoryListResponse;
import core.domain.post.dto.comunity.PostWriteAnonymousAvailableResponse;

import java.util.List;

public interface BoardService {
    List<CategoryListResponse> getCategories();

    PostWriteAnonymousAvailableResponse isAnonymousAvaliable(Long boardId);

}
