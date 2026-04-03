package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessage;
import core.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
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
     * @param keyword    검색할 키워드 문자열
     * @return 키워드가 포함된 메시지 리스트
     */
    List<ChatMessage> findByChatRoomIdAndContentContaining(Long chatRoomId, String keyword);

    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndIdBefore(Long roomId, Instant lastLeftAt, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndSentAtAfter(Long roomId, Instant lastLeftAt, PageRequest sentAt);

    List<ChatMessage> findByChatRoomIdAndIdBefore(Long roomId, Long lastMessageId, PageRequest sentAt);

    List<ChatMessage> findByChatRoomId(Long roomId, PageRequest sentAt);

    Optional<ChatMessage> findTopByChatRoomIdOrderBySentAtDesc(Long roomId);

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

    List<ChatMessage> findTop10ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);

    List<ChatMessage> findByChatRoomIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            Long roomId, Long startId, Long endId);

    Page<ChatMessage> findBySenderId(Long senderId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatMessage m WHERE m.chatRoom.id = :chatRoomId")
    void deleteAllByChatRoomId(@Param("chatRoomId") Long chatRoomId);

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
    @EntityGraph(attributePaths = {"sender"})
    List<ChatMessage> findTop20ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);

    @Query("SELECT cm.chatRoom.isGroup, COUNT(cm) " +
            "FROM ChatMessage cm " +
            "WHERE cm.sentAt BETWEEN :start AND :end " +
            "GROUP BY cm.chatRoom.isGroup")
    List<Object[]> countMessagesByRoomType(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        WITH RoomMessageStats AS (
            SELECT
                cm.chatroom_id,
                cm.sender_id,
                cm.sent_at,
                FIRST_VALUE(cm.sender_id) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as first_sender_id,
                FIRST_VALUE(cm.sent_at) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as first_sent_at
            FROM chat_message cm
            JOIN chat_room cr ON cm.chatroom_id = cr.chatroom_id
            WHERE cr.is_group = false
        ),
        FirstReplies AS (
            SELECT
                chatroom_id,
                first_sent_at,
                MIN(sent_at) as first_reply_at
            FROM RoomMessageStats
            WHERE sender_id != first_sender_id
            GROUP BY chatroom_id, first_sent_at
        )
        SELECT
            COALESCE(AVG(EXTRACT(EPOCH FROM (reply_at - first_sent_at))), 0),
            COUNT(*)
        FROM (
            SELECT
                fr.chatroom_id,
                fr.first_sent_at,
                fr.first_reply_at as reply_at
            FROM FirstReplies fr
            WHERE fr.first_sent_at BETWEEN :start AND :end
        ) final_data
    """, nativeQuery = true)
    List<Object[]> calculateFirstResponseTime(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT
            COALESCE(AVG(EXTRACT(EPOCH FROM (sent_at - prev_sent_at))), 0) as avg_seconds,
            COUNT(*) as count_pairs
        FROM (
            SELECT
                cm.sent_at,
                cm.sender_id,
                LAG(cm.sent_at) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as prev_sent_at,
                LAG(cm.sender_id) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as prev_sender_id
            FROM chat_message cm
            JOIN chat_room cr ON cm.chatroom_id = cr.chatroom_id
            WHERE cr.is_group = true
              AND cm.sent_at BETWEEN :start AND :end
        ) temp_table
        WHERE prev_sent_at IS NOT NULL
          AND prev_sender_id != sender_id
    """, nativeQuery = true)
    List<Object[]> calculateGroupChatAvgReplyTime(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT
            r.room_name,
            COALESCE(AVG(EXTRACT(EPOCH FROM (t.sent_at - t.prev_sent_at))), 0) as avg_diff,
            COUNT(*) as count_pairs
        FROM (
            SELECT
                cm.chatroom_id,
                cm.sent_at,
                cm.sender_id,
                LAG(cm.sent_at) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as prev_sent_at,
                LAG(cm.sender_id) OVER (PARTITION BY cm.chatroom_id ORDER BY cm.sent_at) as prev_sender_id
            FROM chat_message cm
            WHERE cm.sent_at BETWEEN :start AND :end
        ) t
        JOIN chat_room r ON t.chatroom_id = r.chatroom_id
        WHERE r.is_group = true
          AND t.prev_sent_at IS NOT NULL
          AND t.prev_sender_id != t.sender_id -- 자문자답 제외
        GROUP BY r.chatroom_id, r.room_name
        ORDER BY avg_diff ASC
    """, nativeQuery = true)
    List<Object[]> findAllGroupChatSpeeds(@Param("start") Instant start, @Param("end") Instant end);

    long countByChatRoomId(Long chatRoomId);

    boolean existsBySenderIdAndChatRoomIdAndSentAtAfter(Long senderId, Long chatRoomId, Instant sentAt);

    @Query(value = """
    SELECT 
        m.message_id as id, 
        r.chatroom_id as roomId, 
        UPPER(r.room_name || ' (' || r.category || ')') as roomName, 
        u.user_id as userId, 
        CONCAT(u.first_name, ' ', u.last_name, ' (', u.email, ')') as senderName, 
        u.email as senderEmail, 
        m.content as content, 
        m.sent_at as sentAt
    FROM chat_message m
    JOIN (
        SELECT sub_m.message_id 
        FROM chat_message sub_m
        LEFT JOIN users sub_u ON sub_m.sender_id = sub_u.user_id
        WHERE sub_m.sent_at >= :startDate
          AND (:keyword IS NULL OR sub_m.content ILIKE CONCAT('%', CAST(:keyword AS text), '%'))
          AND (:email IS NULL OR sub_u.email ILIKE CONCAT('%', CAST(:email AS text), '%'))
          AND (:name IS NULL OR (sub_u.first_name || sub_u.last_name) ILIKE CONCAT('%', CAST(:name AS text), '%'))
        ORDER BY sub_m.sent_at DESC, sub_m.message_id ASC
        LIMIT :#{#pageable.pageSize} OFFSET :#{#pageable.offset}
    ) temp ON m.message_id = temp.message_id
    JOIN chat_room r ON m.chatroom_id = r.chatroom_id
    JOIN users u ON m.sender_id = u.user_id
    ORDER BY m.sent_at DESC, m.message_id ASC
    """,
            countQuery = """
        SELECT COUNT(*) 
        FROM chat_message m
        LEFT JOIN users u ON m.sender_id = u.user_id
        WHERE m.sent_at >= :startDate  
          AND (:keyword IS NULL OR m.content ILIKE CONCAT('%', CAST(:keyword AS text), '%'))
          AND (:email IS NULL OR u.email ILIKE CONCAT('%', CAST(:email AS text), '%'))
          AND (:name IS NULL OR (u.first_name || u.last_name) ILIKE CONCAT('%', CAST(:name AS text), '%'))
        """,
            nativeQuery = true)
    Page<Object[]> searchMessagesNative(
            @Param("keyword") String keyword,
            @Param("email") String email,
            @Param("name") String name,
            @Param("startDate") LocalDateTime startDate,
            Pageable pageable
    );

}