package core.global.like.repository;

import core.global.enums.LikeType;
import core.global.like.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LikeRepository extends JpaRepository<Like, Long> {

    @Query("select l from Like l where l.relatedId= :id and l.type= :likeType and l.user.name= :name")
    Optional<Like> findLikeByUsernameAndType(@Param("name") String name, @Param("id") Long id, @Param("likeType") LikeType likeType);

    @Query("""
            select l.relatedId, count(l.id)
            from Like l
            where l.type = :type
              and l.relatedId in :ids
            group by l.relatedId
            """)
    List<Object[]> countByRelatedIds(
            @Param("type") LikeType type,
            @Param("ids") List<Long> ids
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByUserNameAndIdAndType(String name, Long id, LikeType likeType);
}
