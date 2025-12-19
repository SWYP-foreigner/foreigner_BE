package core.domain.chat.dto;

public record ChatRoomSearchRequest(
        String keyword,
        String type
) {}
