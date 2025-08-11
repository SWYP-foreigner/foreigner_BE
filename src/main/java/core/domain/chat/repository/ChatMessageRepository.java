package core.domain.chat.repository;


import core.domain.chat.dto.ChatMessageDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChatMessageRepository extends MongoRepository<ChatMessageDoc, String> {
    List<ChatMessageDoc> findByRoomIdOrderByCreatedAtAsc(Long roomId);
}