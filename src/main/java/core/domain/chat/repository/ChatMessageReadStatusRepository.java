package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageReadStatus;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageReadStatusRepository extends JpaRepository<ChatMessageReadStatus, Long> {

    boolean existsByChatMessageAndReader(ChatMessage chatMessage, User reader);
    /**
     * 특정 채팅방에서 특정 사용자가 읽은 메시지 수를 계산합니다.
     * @param chatRoomId 메시지 수를 계산할 채팅방 ID
     * @param readerId 메시지를 읽은 사용자 ID
     * @return 해당 사용자가 읽은 메시지의 총 수
     */
    @Query("SELECT COUNT(cmrs) FROM ChatMessageReadStatus cmrs WHERE cmrs.chatMessage.chatRoom.id = :chatRoomId AND cmrs.reader.id = :readerId")
    long countByChatRoomIdAndReaderId(@Param("chatRoomId") Long chatRoomId, @Param("readerId") Long readerId);
}
