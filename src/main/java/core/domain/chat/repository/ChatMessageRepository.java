package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByChatRoomIdAndContentContaining(Long roomId, String content, Pageable pageable);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndIdBefore(Long roomId, Instant lastLeftAt, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndSentAtAfter(Long roomId, Instant lastLeftAt, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdBefore(Long roomId, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomId(Long roomId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdGreaterThan(Long roomId, Long lastReadMessageId);

    ChatMessage findTopByChatRoomIdOrderBySentAtDesc(Long roomId);

    @Query("SELECT COUNT(cm) FROM ChatMessage cm " +
            "WHERE cm.chatRoom.id = :roomId AND cm.id > " +
            "(SELECT cp.lastReadMessageId FROM ChatParticipant cp WHERE cp.chatRoom.id = :roomId AND cp.user.id = :readerId)")
    int countUnreadMessages(@Param("roomId") Long roomId, @Param("readerId") Long readerId);
}