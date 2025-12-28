package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long>, ChatRoomRepositoryCustom {

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isGroup = true AND LOWER(cr.roomName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<ChatRoom> findGroupChatRoomsByKeyword(@Param("keyword") String keyword);

    List<ChatRoom> findTop10ByIsGroupTrueOrderByCreatedAtDesc();

    @Query("SELECT cr FROM ChatRoom cr " +
            "WHERE cr.isGroup = true " +
            "ORDER BY SIZE(cr.participants) DESC")
    List<ChatRoom> findTopByIsGroupTrueOrderByParticipantCountDesc(int limit);
    List<ChatRoom> findTop10ByIsGroupTrueAndIdLessThanOrderByCreatedAtDesc(Long id);
    /**
     * 특정 사용자가 ACTIVE 상태로 참여하고 있는 채팅방 목록을 조회합니다.
     *
     * @param userId         사용자 ID
     * @param participantStatus 조회할 참여 상태 (ACTIVE)
     * @return ACTIVE 상태인 채팅방 목록
     */
    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN cr.participants cp " +
            "WHERE cp.user.id = :userId AND cp.status = :participantStatus " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM ChatParticipant cp2 " +
            "  WHERE cp2.chatRoom = cr AND cp2.user.provider = 'SYSTEM'" +
            ")")
    List<ChatRoom> findActiveHumanChatRoomsByUserId(@Param("userId") Long userId, @Param("participantStatus") ChatParticipantStatus participantStatus);

    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p JOIN FETCH p.user WHERE cr.id = :roomId")
    Optional<ChatRoom> findByIdWithParticipantsAndUsers(@Param("roomId") Long roomId);

    @EntityGraph(attributePaths = {"participants", "participants.user"})
    @Query("""
        select cr
        from ChatRoom cr
        where cr.isGroup = false
          and (
            select count(distinct cpA.user.id)
            from ChatParticipant cpA
            where cpA.chatRoom = cr
              and cpA.user.id in :userIds
          ) = 2
          and (
            select count(distinct cpB.user.id)
            from ChatParticipant cpB
            where cpB.chatRoom = cr
          ) = 2
    """)
    List<ChatRoom> findOneToOneRoomByParticipantIds(@Param("userIds") List<Long> userIds);
    List<ChatRoom> findAllByOwnerId(Long ownerId);

    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN cr.participants p1 " +
            "JOIN cr.participants p2 " +
            "WHERE cr.isGroup = false " +
            "AND p1.user.id = :userId1 " +
            "AND p2.user.id = :userId2")
    Optional<ChatRoom> findOneToOneChatRoomByParticipants(@Param("userId1") Long userId1, @Param("userId2") Long userId2);


    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN FETCH cr.participants p " +
            "JOIN FETCH p.user u " + // User 엔티티까지 미리 로딩
            "WHERE cr.id = :roomId")
    Optional<ChatRoom> findChatRoomWithParticipantsAndUsers(@Param("roomId") Long roomId);


    /**
     * 추천 가능한 그룹 채팅방 ID 조회
     * - 그룹 채팅방 (isGroup = true)
     * - 추천 가능 (isRecommendable = true)
     * - 내가 'ACTIVE' 상태로 참여 중인 방은 제외 (LEFT인 방은 추천됨)
     */
    @Query("SELECT r.id FROM ChatRoom r " +
            "WHERE r.isGroup= true " +
            "AND r.isRecommendable = true " +
            "AND r.id NOT IN (" +
            "    SELECT p.chatRoom.id " +
            "    FROM ChatParticipant p " +
            "    WHERE p.user.id = :userId " +
            "    AND p.status = core.global.enums.ChatParticipantStatus.ACTIVE" +
            ")")
    List<Long> findRecommendableGroupChatRoomIdsNotJoinedByUserId(@Param("userId") Long userId);

    /**
     * [스케줄러용] 특정 유저(userId)와 대화한 적 있는 '상대방 유저 ID' 목록 조회
     * 동작 원리:
     * 1. ChatRoom(c)을 기준으로
     * 2. p1(나)이 참여해 있고
     * 3. p2(상대방)도 참여해 있는 방을 찾아서
     * 4. p2의 ID만 싹 긁어옴 (DISTINCT로 중복 제거)
     */
    @Query("SELECT DISTINCT p2.user.id FROM ChatRoom c " +
            "JOIN c.participants p1 " +
            "JOIN c.participants p2 " +
            "WHERE p1.user.id = :userId " +
            "AND p2.user.id != :userId")
    List<Long> findPartnerIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(DISTINCT c) FROM ChatRoom c " +
            "JOIN c.participants p1 " +
            "JOIN c.participants p2 " +
            "WHERE p1.user.id = :userId " +
            "AND p2.user.userRole = 'AI' " +
            "AND c.isGroup = false")
    long countAiChatRoomsByUser(@Param("userId") Long userId);

    List<ChatRoom> findByIsGroupTrue();
    @Query("SELECT c.isGroup FROM ChatRoom c WHERE c.id = :roomId")
    boolean isGroupChat(@Param("roomId") Long roomId);

    @Query("SELECT COUNT(c) FROM ChatRoom c " +
            "JOIN c.participants p " +
            "WHERE p.user.id = :userId " +
            "AND c.isGroup = false " +
            "AND (SELECT COUNT(m) FROM ChatMessage m WHERE m.chatRoom = c AND m.sender.id = :userId) = 0")
    long countUnrepliedAiRooms(@Param("userId") Long userId);
}