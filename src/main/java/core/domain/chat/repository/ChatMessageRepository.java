package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * 특정 채팅방의 가장 최근 메시지를 조회합니다.
     * chatRoomId로 메시지를 찾고, sentAt 필드를 기준으로 내림차순 정렬하여 첫 번째 결과를 반환합니다.
     *
     * @param chatRoomId 메시지를 찾을 채팅방의 ID
     * @return 가장 최근 메시지가 담긴 Optional 객체
     */
    Optional<ChatMessage> findFirstByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
    /**
     * 특정 채팅방의 전체 메시지 수를 계산합니다.
     * @param chatRoomId 메시지 수를 계산할 채팅방 ID
     * @return 해당 채팅방의 총 메시지 수
     */
    long countByChatRoomId(Long chatRoomId);
}