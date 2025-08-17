package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByChatRoomIdOrderBySentAtAsc(Long chatRoomId);
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatRoom.id = :roomId AND cm.readCount < :memberCount")
    List<ChatMessage> findUnreadMessages(@Param("roomId") Long roomId, @Param("memberCount") int memberCount);

    List<ChatMessage> findByChatRoomIdAndContentContaining(Long roomId, String keyword);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterOrderBySentAtAsc(Long roomId, Instant lastLeftAt);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndIdBefore(Long roomId, Instant lastLeftAt, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndSentAtAfter(Long roomId, Instant lastLeftAt, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdBefore(Long roomId, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomId(Long roomId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdGreaterThan(Long roomId, Long lastReadMessageId);

    List<ChatMessage> findByChatRoomIdAndIdBeforeAndSentAtAfterOrderBySentAtDesc(Long roomId, Long lastMessageId, Instant lastLeftAt, PageRequest of);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterOrderBySentAtDesc(Long roomId, Instant lastLeftAt, PageRequest of);

    List<ChatMessage> findByChatRoomIdAndIdBeforeOrderBySentAtDesc(Long roomId, Long lastMessageId, PageRequest of);

    List<ChatMessage> findByChatRoomIdOrderBySentAtDesc(Long roomId, PageRequest of);
}