package core.global.service;

import core.domain.post.dto.PostListResponse;
import core.domain.post.dto.PostSearchRequest;
import core.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostAdminService {

    private final PostRepository postRepository;

    @Transactional(readOnly = true)
    public Page<PostListResponse> searchPosts(PostSearchRequest request, Pageable pageable) {
        return postRepository.searchPosts(request, pageable);
    }
}
