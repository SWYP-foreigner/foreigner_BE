package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.global.enums.chat.ChatParticipantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findByChatRoomId(Long chatRoomId);
    Page<ChatParticipant> findByChatRoomId(Long chatRoomId, Pageable pageable);

    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndStatusIsNot(Long chatRoomId, Long userId, ChatParticipantStatus status);

    @Query("SELECT cp FROM ChatParticipant cp WHERE cp.chatRoom.id = :roomId AND cp.user.id = :userId")
    Optional<ChatParticipant> findByChatRoomIdAndUserId(@Param("roomId") Long roomId, @Param("userId") Long userId);



    long countByChatRoomIdAndStatus(Long roomId, ChatParticipantStatus status);

    @Query(
        "SELECT cr FROM ChatRoom cr " +
                "WHERE cr.id IN (SELECT cp.chatRoom.id FROM ChatParticipant cp WHERE cp.user.id = :userId) " +
                "AND (" +
                "   (cr.isGroup = true AND cr.roomName LIKE %:keyword%) " +
                "   OR " +
                "   (cr.isGroup = false AND EXISTS (" +
                "       SELECT 1 FROM ChatParticipant cp2 " +
                "       JOIN cp2.user u " +
                "       WHERE cp2.chatRoom = cr AND cp2.user.id != :userId " +
                "       AND CONCAT(u.firstName, u.lastName) LIKE %:keyword%" +
                "   ))" +
                ")"
    )
    List<ChatRoom> findChatRoomsByUserIdAndRoomName(@Param("userId") Long userId, @Param("keyword") String keyword);

    @Modifying
    @Query("DELETE FROM ChatParticipant p WHERE p.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);



    List<ChatParticipant> findAllByChatRoomIdAndUserIdNot(Long chatRoomId, Long userId);

    Page<ChatParticipant> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT u.firstName FROM ChatParticipant cp JOIN cp.user u WHERE cp.chatRoom.id = :roomId")
    List<String> findParticipantNamesByRoomId(@Param("roomId") Long roomId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ChatParticipant cp WHERE cp.chatRoom.id = :roomId")
    void deleteByChatRoomId(@Param("roomId") Long roomId);

    @Query("SELECT cp FROM ChatParticipant cp " +
            "JOIN FETCH cp.user u " +
            "WHERE cp.chatRoom.id = :roomId " +
            "AND cp.status = 'ACTIVE'")
    List<ChatParticipant> findActiveParticipants(@Param("roomId") Long roomId);

    @Query("SELECT DISTINCT cp.chatRoom.id " +
            "FROM ChatParticipant cp " +
            "WHERE cp.user.userRole = 'AI' " +
            "AND cp.status = 'ACTIVE'")
    List<Long> findAllAiParticipatedRoomIds();

    Page<ChatParticipant> findByUserIdAndStatus(Long userId, ChatParticipantStatus status, Pageable pageable);
    List<ChatParticipant> findAllByChatRoomId(Long chatRoomId);
}