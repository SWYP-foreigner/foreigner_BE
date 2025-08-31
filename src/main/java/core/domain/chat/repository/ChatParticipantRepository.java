package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findByChatRoomId(Long chatRoomId);

    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndStatusIsNot(Long chatRoomId, Long userId, ChatParticipantStatus status);

    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    long countByChatRoomIdAndStatus(Long roomId, ChatParticipantStatus status);

    @Query("SELECT cp.chatRoom FROM ChatParticipant cp WHERE cp.user.id = :userId AND cp.chatRoom.roomName LIKE %:roomName%")
    List<ChatRoom> findChatRoomsByUserIdAndRoomName(@Param("userId") Long userId, @Param("roomName") String roomName);

}