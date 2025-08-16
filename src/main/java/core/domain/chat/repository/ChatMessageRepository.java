package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // 특정 채팅방의 메시지 목록을 가져오는 메서드
    List<ChatMessage> findByChatRoomIdOrderBySentAtAsc(Long chatRoomId);
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatRoom.id = :roomId AND cm.readCount < :memberCount")
    List<ChatMessage> findUnreadMessages(@Param("roomId") Long roomId, @Param("memberCount") int memberCount);

    List<ChatMessage> findByChatRoomIdAndContentContaining(Long roomId, String keyword);

}