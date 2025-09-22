package core.domain.post.repository;

import core.domain.post.entity.BlockPost;
import feign.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BlockPostRepository extends JpaRepository<BlockPost, Long> {

    @Query("select count(b)>0 from BlockPost b where b.user.id=:userId and b.post.id=:postId")
    boolean existsBlock(@Param("userId") Long userId, @Param("postId") Long postId);
}
