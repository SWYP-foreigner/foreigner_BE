package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findByChatRoomId(Long chatRoomId);

    boolean existsByChatRoomIdAndUserId(Long roomId, Long id);

    int countByChatRoomId(Long roomId);

    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndDeletedFalse(Long chatRoomId, Long userId);

    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    Optional<Object> findByChatRoomAndUser(ChatRoom room, User blocker);

    List<ChatParticipant> findByChatRoomIdAndUserIdIn(Long roomId, List<Long> userIds);
}
