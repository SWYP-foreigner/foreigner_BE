package core.domain.chat.repository;

import core.domain.chat.dto.ChatMessageSearchRequest;
import core.domain.chat.dto.ChatMessageSearchResultDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatMessageRepositoryCustom {
    Page<ChatMessageSearchResultDto> searchMessages(ChatMessageSearchRequest condition, Pageable pageable);
}
