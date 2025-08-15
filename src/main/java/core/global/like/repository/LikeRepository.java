package core.global.like.repository;

import core.global.enums.LikeType;
import core.global.like.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LikeRepository extends JpaRepository<Like, Long> {

    @Query("select l from Like l where l.relatedId= :postId and l.type= :likeType and l.user.name= :username")
    Optional<Like> findLikeByUsernameAndType(@Param("username") String username, @Param("postId") Long postId, @Param("likeType") LikeType likeType);

    @Query("""
    select l.relatedId, count(l.id)
    from Like l
    where l.type = :type
      and l.relatedId in :commentIds
    group by l.relatedId
    """)
    List<Object[]> countByRelatedIds(
            @Param("type") LikeType type,
            @Param("commentIds") List<Long> commentIds
    );

    @Query("""
    select l.relatedId
    from Like l
    where l.type = :type
      and l.user.id = :userId
      and l.relatedId in :commentIds
    """)
    List<Long> findRelatedIdsLikedByUser(
            @Param("type") LikeType type,
            @Param("userId") Long userId,
            @Param("commentIds") List<Long> commentIds
    );
}
