package core.global.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.search.SearchConstants;
import core.global.search.dto.PostDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PostIndexService {

    private final ElasticsearchClient searchClient;

    public void index(Long postId, PostDocument doc) {
        try {
            searchClient.index(i -> i
                    .index(SearchConstants.INDEX_POSTS_WRITE)
                    .id(String.valueOf(postId))   // ES _id = postId
                    .document(doc)
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_INDEX_FAILED);
        }
    }

    // 삭제
    public void deleteById(Long postId) {
        try {
            searchClient.delete(d -> d
                    .index(SearchConstants.INDEX_POSTS_WRITE)
                    .id(String.valueOf(postId))
            );
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ex) {
            if (ex.response() == null || ex.response().status() != 404) {
                throw new BusinessException(ErrorCode.ELASTICSEARCH_INDEX_FAILED);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_INDEX_FAILED);
        }
    }
}