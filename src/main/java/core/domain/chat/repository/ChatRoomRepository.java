package core.domain.chat.repository;

import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /**
     * 특정 사용자 ID 목록에 포함된 1:1 채팅방을 찾습니다.
     * 이 쿼리는 정확히 두 명의 사용자가 참여한 1:1 채팅방을 반환합니다.
     */
    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN cr.participants p " +
            "WHERE cr.group = false AND p.user.id IN :userIds " + // 여기서 'group'으로 수정
            "GROUP BY cr.id " +
            "HAVING COUNT(p.user.id) = 2")
    Optional<ChatRoom> findOneOnOneRoomByParticipantIds(@Param("userIds") List<Long> userIds);


    /**
     * 특정 사용자가 속한 모든 채팅방 목록을 찾습니다.
     */
    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN cr.participants p " +
            "WHERE p.user.id = :userId")
    List<ChatRoom> findChatRoomsByUserId(@Param("userId") Long userId);


    Optional<ChatRoom> findByParticipantIds(Long currentUserId, Long otherUserId);
}