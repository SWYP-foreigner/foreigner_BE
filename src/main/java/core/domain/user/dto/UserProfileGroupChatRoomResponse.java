package core.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
@Schema(description = "유저 프로필용 참여 그룹 채팅방 응답 DTO")
public class UserProfileGroupChatRoomResponse {

    @Schema(description = "채팅방 고유 ID", example = "501")
    private Long chatRoomId;

    @Schema(description = "채팅방 이름", example = "충북대학교 글로벌 커뮤니티")
    private String roomName;

    @Schema(description = "채팅방 간단 설명", example = "다양한 국적의 친구들과 대화하는 공간입니다.(일정 글자 넘어가면 프론트에서 글자수 컷 가정")
    private String description;

    @Schema(description = "채팅방 대표 이미지 URL", example = "https://cdn.kori.com/rooms/thumb_501.png")
    private String thumbnailUrl;

    @Schema(description = "현재 참여 중인 인원 수", example = "24")
    private int participantCount;

    @Schema(description = "마지막 메시지가 전송된 시간 (정렬 기준)", example = "2026-02-18T11:12:00Z")
    private Instant lastMessageSentAt;
}