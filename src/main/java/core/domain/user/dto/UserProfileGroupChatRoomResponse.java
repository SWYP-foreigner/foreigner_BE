package core.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
@Schema(description = "유저 프로필용 참여 그룹 채팅방 응답 DTO")
public class UserProfileGroupChatRoomResponse {

    @Schema(description = "채팅방 ID", example = "501")
    private Long roomId; // chatRoomId -> roomId

    @Schema(description = "채팅방 이름", example = "충북대학교 글로벌 커뮤니티")
    private String roomName;

    @Schema(description = "채팅방 간단 설명", example = "다양한 국적의 친구들과 대화하는 공간입니다.")
    private String description;

    @Schema(description = "채팅방 대표 이미지 URL", example = "https://cdn.kori.com/rooms/thumb_501.png")
    private String roomImageUrl; // thumbnailUrl -> roomImageUrl

    @Schema(description = "현재 참여 중인 인원 수", example = "24")
    private String userCount; // participantCount(int) -> userCount(String) 타입 변경

    @Schema(description = "마지막 메시지가 전송된 시간 (정렬 기준)", example = "2026-02-18T11:12:00Z")
    private Instant lastMessageSentAt;
}