package core.domain.chat.repository;

import core.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = true AND cr.roomName LIKE %:keyword%")
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
}