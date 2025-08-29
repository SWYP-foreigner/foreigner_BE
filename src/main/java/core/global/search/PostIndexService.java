package core.global.search;

@Service
@RequiredArgsConstructor
public class PostIndexService {

    private final ElasticsearchClient es;

    public PostDocument toDocument(Post p) {
        return PostDocument.builder()
                .postId(p.getId())
                .boardId(p.getBoard().getId())
                .userId(p.getAuthor().getId())
                .anonymous(Boolean.TRUE.equals(p.getAnonymous()))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .checkCount(p.getCheckCount())
                .content(p.getContent())
                .build();
    }

    @Transactional(readOnly = true)
    public void index(Post post) {
        try {
            PostDocument doc = toDocument(post);
            IndexResponse res = es.index(i -> i
                    .index(SearchConstants.INDEX_POSTS)
                    .id(String.valueOf(doc.getPostId())) // ES 문서 id = RDB post_id
                    .document(doc)
            );
            // 필요시 res.result() 확인하여 CREATED/UPDATED 로깅
        } catch (Exception e) {
            throw new RuntimeException("Elasticsearch index failed: " + e.getMessage(), e);
        }
    }
}