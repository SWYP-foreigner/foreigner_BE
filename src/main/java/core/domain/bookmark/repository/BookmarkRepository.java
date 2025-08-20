package core.domain.bookmark.repository;

import core.domain.bookmark.entity.Bookmark;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {
    @EntityGraph(attributePaths = {"post", "post.author"})
    Slice<Bookmark> findByUserNameOrderByIdDesc(String userName, Pageable pageable);

    @EntityGraph(attributePaths = {"post", "post.author"})
    Slice<Bookmark> findByUserNameAndIdLessThanOrderByIdDesc(String userName, Long id, Pageable pageable);

    Optional<Bookmark> findByUserNameAndPostId(String userName, Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByUserNameAndPostId(String username, Long postId);
}
