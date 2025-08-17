package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findByChatRoomId(Long chatRoomId);

    boolean existsByChatRoomIdAndUserId(Long roomId, Long id);

    int countByChatRoomId(Long roomId);

    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndStatusIsNot(Long chatRoomId, Long userId, ChatParticipantStatus status);

    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    Optional<ChatParticipant> findByChatRoomAndUser(ChatRoom room, User user);

    List<ChatParticipant> findByChatRoomIdAndUserIdIn(Long roomId, List<Long> userIds);

    @Query("SELECT cp FROM ChatParticipant cp WHERE cp.user.id = :userId")
    List<ChatParticipant> findByUserIdExplicit(@Param("userId") Long userId);
}