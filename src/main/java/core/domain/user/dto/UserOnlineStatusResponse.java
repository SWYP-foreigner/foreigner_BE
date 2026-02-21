package core.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
@Schema(description = "유저의 접속 상태 정보 응답")
public class UserOnlineStatusResponse {

    @Schema(description = "현재 온라인 여부 (true: 접속 중, false: 미접속)", example = "true")
    private boolean isOnline;

    @Schema(
            description = "마지막 활동 시간 (온라인일 경우 현재에 가까운 시간, 오프라인일 경우 마지막 로그아웃 시점)",
            example = "2026-02-18T11:12:00Z"
    )
    private Instant lastSeenAt;
}