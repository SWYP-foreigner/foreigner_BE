package core.domain.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GroupChatSpeedDto {
    private String formattedTime;
    private long averageSeconds;
    private long analyzedMessageCount;
}
