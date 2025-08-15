package core.domain.comment.repository;

import core.domain.comment.entity.Comment;
import core.global.enums.LikeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = {"author", "post"})
    Slice<Comment> findByPostId(Long postId, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "post"})
    @Query("""
    select c
    from Comment c
    join c.post p
    where p.id = :postId
      and (
            c.createdAt < :cursorCreatedAt
         or (c.createdAt = :cursorCreatedAt and c.id < :cursorId)
      )
    order by c.createdAt desc, c.id desc
    """)
    Slice<Comment> findCommentByCursor(
            @Param("postId") Long postId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );


    @EntityGraph(attributePaths = {"author", "post"})
    @Query("""
    select c
    from Comment c
    join c.post p
    where p.id = :postId
    order by
      (select count(l1.id) from Like l1 where l1.type = :type and l1.relatedId = c.id) desc,
      c.createdAt desc,
      c.id desc
    """)
    Slice<Comment> findPopularByPostId(
            @Param("postId") Long postId,
            @Param("type") LikeType type,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"author", "post"})
    @Query("""
    select c
    from Comment c
    join c.post p
    where p.id = :postId
      and (
        ( (select count(l2.id) from Like l2 where l2.type = :type and l2.relatedId = c.id) < :cursorLikeCount )
        or (
             (select count(l3.id) from Like l3 where l3.type = :type and l3.relatedId = c.id) = :cursorLikeCount
             and ( c.createdAt < :cursorCreatedAt
                   or (c.createdAt = :cursorCreatedAt and c.id < :cursorId)
                 )
           )
      )
    order by
      (select count(l4.id) from Like l4 where l4.type = :type and l4.relatedId = c.id) desc,
      c.createdAt desc,
      c.id desc
    """)
    Slice<Comment> findPopularByCursor(
            @Param("postId") Long postId,
            @Param("type") LikeType type,
            @Param("cursorLikeCount") Long cursorLikeCount,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );


}
