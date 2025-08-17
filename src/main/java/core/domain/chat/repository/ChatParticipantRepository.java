package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
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
}
