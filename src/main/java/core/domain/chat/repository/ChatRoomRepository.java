package core.domain.chat.repository;

import core.domain.chat.entity.ChatRoom;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = true AND LOWER(cr.roomName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<ChatRoom> findGroupChatRoomsByKeyword(@Param("keyword") String keyword);
    /**
     * 특정 사용자가 속한 모든 채팅방 목록을 찾습니다.
     */
    @Query("SELECT cr FROM ChatRoom cr " +
           "JOIN cr.participants p " +
           "WHERE p.user.id = :userId")
    List<ChatRoom> findChatRoomsByUserId(@Param("userId") Long userId);

    @Query("""
            select distinct cr
            from ChatRoom cr
            left join fetch cr.participants cp
            left join fetch cp.user u
            where exists (
              select 1 from ChatParticipant c1
              where c1.chatRoom = cr and c1.user.id = :currentUserId
            )
            and exists (
              select 1 from ChatParticipant c2
              where c2.chatRoom = cr and c2.user.id = :otherUserId
            )
            and size(cr.participants) = 2
            """)
    Optional<ChatRoom> findByParticipantIds(Long currentUserId, Long otherUserId);

    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p JOIN FETCH p.user WHERE cr.id = :roomId")
    Optional<ChatRoom> findByIdWithParticipants(@Param("roomId") Long roomId);

    List<ChatRoom> findTop10ByGroupTrueOrderByCreatedAtDesc();

    @Query("SELECT cr FROM ChatRoom cr " +
            "WHERE cr.group = true " +
            "ORDER BY SIZE(cr.participants) DESC")
    List<ChatRoom> findTopByGroupTrueOrderByParticipantCountDesc(int limit);

    List<ChatRoom> findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(Long id);
    /**
     * 특정 사용자가 ACTIVE 상태로 참여하고 있는 채팅방 목록을 조회합니다.
     *
     * @param userId         사용자 ID
     * @param participantStatus 조회할 참여 상태 (ACTIVE)
     * @return ACTIVE 상태인 채팅방 목록
     */
    @Query("SELECT cr FROM ChatRoom cr JOIN ChatParticipant cp ON cr.id = cp.chatRoom.id WHERE cp.user.id = :userId AND cp.status = :participantStatus")
    List<ChatRoom> findActiveChatRoomsByUserId(@Param("userId") Long userId, @Param("participantStatus") ChatParticipantStatus participantStatus);
    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p JOIN FETCH p.user WHERE cr.id = :roomId")
    Optional<ChatRoom> findByIdWithParticipantsAndUsers(@Param("roomId") Long roomId);

}