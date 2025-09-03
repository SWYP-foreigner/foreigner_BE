package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import core.domain.post.entity.Post;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostIndexService {

    private final ElasticsearchClient es;

    public PostDocument toDocument(Post p) {
        return new PostDocument(p);
    }

    @Transactional(readOnly = true)
    public void index(Post post) {
        try {
            PostDocument doc = toDocument(post);
            IndexResponse res = es.index(i -> i
                    .index(SearchConstants.INDEX_POSTS_WRITE)
                    .id(String.valueOf(doc.postId())) // ES 문서 id = RDB post_id
                    .document(doc)
            );
            // 필요시 res.result() 확인하여 CREATED/UPDATED 로깅
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_INDEX_FAILED);
        }
    }
}