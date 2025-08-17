package core.domain.chat.repository;


import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    List<ChatRoom> find1on1RoomByParticipants(List<Long> creatorId);

    List<ChatRoom> findChatRoomsByUserId(Long userId);
}
