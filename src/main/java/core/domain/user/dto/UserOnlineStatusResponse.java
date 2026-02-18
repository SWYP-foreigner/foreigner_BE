package core.domain.user.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
public class UserOnlineStatusResponse {
    private boolean isOnline;      // 접속 여부 (true: 접속중, false: 미접속)
    private Instant lastSeenAt;    // 마지막 활동 시간
}