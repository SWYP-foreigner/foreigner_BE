package core.domain.user.dto;
import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
public class UserProfileGroupChatRoomResponse {
    private Long chatRoomId;
    private String roomName;
    private String description;
    private String thumbnailUrl; // 채팅방 대표 이미지
    private int participantCount; // 현재 참여 인원 수
    private Instant lastMessageSentAt; // 정렬을 위해 필요 (최신순)

}