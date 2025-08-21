package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageReadStatus;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageReadStatusRepository extends JpaRepository<ChatMessageReadStatus, Long> {

    boolean existsByChatMessageAndReader(ChatMessage chatMessage, User reader);
    List<ChatMessage> findByChatMessage_ChatRoomIdAndChatMessageIdGreaterThan(Long chatRoomId, Long messageId);
}
