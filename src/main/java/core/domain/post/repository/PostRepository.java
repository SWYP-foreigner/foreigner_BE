package core.domain.post.repository;

import core.domain.post.entity.Post;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> , PostRepositoryCustom{
    @Query("SELECT p.author FROM Post p WHERE p.id = :postId")
    Optional<User> findUserByPostId(Long postId);
    @Modifying
    @Query("DELETE FROM Post p WHERE p.author.id = :userId")
    void deleteAllByAuthorId(@Param("userId") Long userId);
}
