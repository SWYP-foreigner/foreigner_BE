package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 특정 채팅방의 모든 메시지를 메시지 ID 오름차순으로 조회합니다.
     * (메시지 생성 순서대로 정렬)
     *
     * @param chatRoomId 조회할 채팅방의 ID
     * @return 해당 채팅방의 모든 메시지 리스트
     */
    List<ChatMessage> findByChatRoomIdOrderByIdAsc(Long chatRoomId);

    /**
     * 특정 채팅방에서 주어진 키워드가 포함된 메시지를 검색합니다.
     * (SQL의 LIKE '%keyword%'와 동일)
     *
     * @param chatRoomId 검색할 채팅방의 ID
     * @param keyword 검색할 키워드 문자열
     * @return 키워드가 포함된 메시지 리스트
     */
    List<ChatMessage> findByChatRoomIdAndContentContaining(Long chatRoomId, String keyword);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndIdBefore(Long roomId, Instant lastLeftAt, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndSentAtAfter(Long roomId, Instant lastLeftAt, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdBefore(Long roomId, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomId(Long roomId, PageRequest sentAt);

    List<ChatMessage> findTop50ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);


    Optional<ChatMessage> findTopByChatRoomIdOrderBySentAtDesc(Long roomId);
    /**
     * 특정 채팅방의 가장 최근 메시지를 조회합니다.
     * chatRoomId로 메시지를 찾고, sentAt 필드를 기준으로 내림차순 정렬하여 첫 번째 결과를 반환합니다.
     *
     * @param chatRoomId 메시지를 찾을 채팅방의 ID
     * @return 가장 최근 메시지가 담긴 Optional 객체
     */
    Optional<ChatMessage> findFirstByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
    @Query("SELECT COUNT(m) FROM ChatMessage m " +
            "WHERE m.chatRoom.id = :roomId " +
            "  AND m.id > :lastReadId " +
            "  AND m.sender.id != :userId")
    int countUnreadMessages(@Param("roomId") Long roomId,
                            @Param("lastReadId") Long lastReadId,
                            @Param("userId") Long userId);
    /**
     * 특정 채팅방(chatRoomId)에서, 특정 메시지 ID(id)보다 큰 ID를 가진 메시지들의 개수를 반환합니다.
     * Spring Data JPA가 메서드 이름을 분석하여 아래와 유사한 쿼리를 자동으로 생성합니다:
     * SELECT COUNT(cm) FROM ChatMessage cm WHERE cm.chatRoom.id = :chatRoomId AND cm.id > :id
     */
    Optional<ChatMessage> findTopByChatRoomIdOrderByIdDesc(Long chatRoomId);
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.sender.id = :userId")
    void deleteAllBySenderId(@Param("userId") Long userId);
}