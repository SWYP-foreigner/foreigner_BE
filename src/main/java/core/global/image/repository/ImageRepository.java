package core.global.image.repository;

import core.global.image.entity.Image;
import core.global.image.entity.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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

    List<Image> findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType imageType, Long relatedId);

    @Modifying
    @Query("""
        delete from Image i
        where i.imageType = :imageType
          and i.relatedId  = :relatedId
          and i.url in :urls
    """)
    void deleteByImageTypeAndRelatedIdAndUrlIn(ImageType imageType, Long relatedId, Collection<String> urls);

    @Modifying
    @Query("""
        delete from Image i
         where i.imageType = :imageType
           and i.relatedId  = :relatedId
    """)
    void deleteByImageTypeAndRelatedId(ImageType imageType, Long postId);
}
