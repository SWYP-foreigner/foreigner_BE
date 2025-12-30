package core.domain.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GroupChatRankingDto {
    private int rank;
    private String roomName;
    private String formattedTime;
    private long messageCount;
}
