package core.domain.chat.dto;
import core.domain.chat.dto.ChatRoomSummaryResponse;
import core.domain.chat.dto.MessageReadCountUpdateResponse;
import core.domain.chat.dto.ReadCountInfo;
import java.util.List;
public record MessageReadEvent(
        Long roomId,
        List<ReadCountInfo> updatedReadCounts,
        Long readerId,
        ChatRoomSummaryResponse roomSummary
) {}