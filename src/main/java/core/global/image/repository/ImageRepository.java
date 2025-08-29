package core.global.image.repository;

import com.mongodb.client.MongoIterable;
import core.global.image.entity.Image;
import core.global.enums.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<Image, Long> {
    @Query("""
    select i.relatedId, i.url
    from Image i
    where i.imageType = :imageType
      and i.relatedId in :commentIds
    """)
    List<Object[]> findUrlByRelatedIds(
            @Param("imageType") ImageType imageType,
            @Param("commentIds") List<Long> commentIds
    );

    @Query("""
        select i.relatedId, i.url
        from Image i
        where i.id in (
            select min(i2.id)
            from Image i2
            where i2.imageType = :imageType
              and i2.relatedId in :relatedIds
            group by i2.relatedId
        )
    """)
    List<Object[]> findFirstUrlByRelatedIds(@Param("imageType") ImageType imageType,
                                            @Param("relatedIds") List<Long> relatedIds);

    @Query("""
        select i.relatedId, i.url
        from Image i
        where i.imageType = :imageType
          and i.relatedId in :relatedIds
        order by i.id asc
    """)
    List<Object[]> findAllUrlsByRelatedIds(@Param("imageType") ImageType imageType,
                                           @Param("relatedIds") List<Long> relatedIds);

    @Query("select i from Image i " +
           "where i.imageType = :type and i.relatedId = :relatedId " +
           "order by i.orderIndex asc")
    List<Image> findByImageTypeAndRelatedIdOrderByPositionAsc(
            @Param("type") ImageType type,
            @Param("relatedId") Long relatedId
    );

    @Modifying
    @Query("""
        delete from Image i
        where i.imageType = :imageType
          and i.relatedId  = :relatedId
          and i.url in :urls
    """)
    void deleteByImageTypeAndRelatedIdAndUrlIn(ImageType imageType, Long relatedId, Collection<String> urls);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from Image i
         where i.imageType = :imageType
           and i.relatedId  = :relatedId
    """)
    void deleteByImageTypeAndRelatedId(@Param("imageType") ImageType imageType,
                                       @Param("relatedId") Long relatedId);

    void deleteByImageTypeAndRelatedIdAndUrlIn(ImageType imageType, Long relatedId, List<String> urls);

    List<Image> findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType imageType, Long relatedId);

    Optional<Image> findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType imageType, Long relatedId);


    List<Image> findByImageTypeAndRelatedId(ImageType imageType, Long relatedId);

    // ✅ 쿼리 메소드 추가 (메서드명으로 JPA가 자동 생성)



}
