package core.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "채팅방 알림 상태 응답 DTO")
public record ChatNotificationStatusResponse(
        @Schema(description = "알림 활성화 여부 (true: 켱, false: 끔)")
        boolean enabled
) { }