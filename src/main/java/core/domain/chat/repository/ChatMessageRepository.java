package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import core.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long>, ChatMessageRepositoryCustom {

    void deleteByChatRoomId(Long chatRoomId);
    @Query("SELECT cm FROM ChatMessage cm " +
            "WHERE cm.id IN (" +
            "    SELECT MAX(m.id) FROM ChatMessage m " +
            "    WHERE m.chatRoom.id IN :roomIds " +
            "    GROUP BY m.chatRoom.id" +
            ")")
    List<ChatMessage> findLastMessagesByRoomIds(@Param("roomIds") List<Long> roomIds);

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

    /**
     * 특정 메시지 ID보다 작은(이전) 메시지들을 최신순으로 20개 조회합니다.
     */
    List<ChatMessage> findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long messageId);

    /**
     * 특정 메시지 ID보다 큰(이후) 메시지들을 순서대로 20개 조회합니다.
     */
    List<ChatMessage> findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(Long roomId, Long messageId);
    Optional<ChatMessage> findFirstByChatRoomIdAndSenderNotInOrderBySentAtDesc(Long chatRoomId, List<User> senders);
    List<ChatMessage> findTop10ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
    List<ChatMessage> findByChatRoomIdOrderBySentAtAsc(Long chatRoomId);

    /**
     * 무한 스크롤을 위한 메시지 조회 (커서 기반)
     * @param roomId 채팅방 ID
     * @param lastMessageId 마지막으로 조회된 메시지의 ID (커서)
     * @param pageable 페이지 크기 정보 (항상 20개씩)
     * @return Slice<ChatMessage> - hasNext()로 다음 페이지 유무 확인 가능
     */
    Slice<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long lastMessageId, Pageable pageable);
    Slice<ChatMessage> findByChatRoomIdOrderByIdDesc(Long roomId, Pageable pageable);

    List<ChatMessage> findByChatRoomIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            Long roomId, Long startId, Long endId);

    Page<ChatMessage> findBySenderId(Long senderId, Pageable pageable);

    void deleteAllByChatRoomId(Long chatRoomId);

    @Query(value = "SELECT COUNT(*) FROM chat_message WHERE sent_at >= NOW() - INTERVAL '7 days'", nativeQuery = true)
    long countMessagesLast7Days();

    @Query(
            value = """
            SELECT COUNT(DISTINCT sender_id)
            FROM chat_message
            WHERE sent_at >= NOW() - INTERVAL '1 day'
            """,
            nativeQuery = true
    )
    Long countSendMessageUsersLast1Day();

    Page<ChatMessage> findAllByChatRoomId(Long chatRoomId, Pageable pageable);
}