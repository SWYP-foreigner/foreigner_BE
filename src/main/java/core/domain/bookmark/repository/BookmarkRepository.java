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
    Slice<Bookmark> findByUserEmailOrderByIdDesc(String email, Pageable pageable);

    @EntityGraph(attributePaths = {"post", "post.author"})
    Slice<Bookmark> findByUserEmailAndIdLessThanOrderByIdDesc(String email, Long id, Pageable pageable);

    Optional<Bookmark> findByUserEmailAndPostId(String email, Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void deleteByUserEmailAndPostId(String email, Long postId);
}
