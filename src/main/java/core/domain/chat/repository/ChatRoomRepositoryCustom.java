package core.domain.chat.repository;

import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatRoomRepositoryCustom {
    Page<ChatRoomListResponse> searchChatRooms(ChatRoomSearchRequest condition, Pageable pageable);
}
