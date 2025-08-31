package core.domain.comment.repository;

import core.domain.comment.dto.UserCommentItem;
import core.domain.comment.entity.Comment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long>, CommentRepositoryCustom {

    Boolean existsByParentIdAndDeletedFalse(Long commentId);

    Long countByParentId(Long id);

    @Query("""
                select c.post.id, count(c.id)
                from Comment c
                where c.post.id in :postIds
                group by c.post.id
            """)
    List<Object[]> countByPostIds(@Param("postIds") List<Long> postIds);

    @Query("""
            select new core.domain.comment.dto.UserCommentItem(c.id, p.content, c.content, c.createdAt)
            from Comment c
            join c.post p
            where c.author.email = :email
            and (:lastId is null or c.id < :lastId)
            order by c.id desc
            """)
    List<UserCommentItem> findMyCommentsForCursor(
            @Param("templates/email") String email,
            @Param("lastId") Long lastId,
            Pageable pageable
    );

}
