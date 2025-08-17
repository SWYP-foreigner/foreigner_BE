package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findByChatRoomId(Long chatRoomId);

    boolean existsByChatRoomIdAndUserId(Long roomId, Long id);

    int countByChatRoomId(Long roomId);

    // [수정] 'deleted' 필드 대신 'status' 필드를 사용하여 'LEFT' 상태가 아닌 참여자를 찾도록 변경
    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndStatusIsNot(Long chatRoomId, Long userId, ChatParticipantStatus status);

    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    // [수정] 반환 타입을 ChatParticipant로 명확하게 변경
    Optional<ChatParticipant> findByChatRoomAndUser(ChatRoom room, User user);

    List<ChatParticipant> findByChatRoomIdAndUserIdIn(Long roomId, List<Long> userIds);
}