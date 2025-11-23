package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.service.ChatMemberService;
import core.domain.chat.service.ChatService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import core.global.metrics.FeatureUsageMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "채팅 멤버 및 설정 API", description = "참여자 조회, 신고/차단, 알림/번역 설정 등")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatMemberController {

    private final ChatMemberService chatService;
    private final FeatureUsageMetrics featureUsageMetrics;

    @Operation(summary = "채팅 참여자 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomParticipantsResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatRoomParticipantsResponse>>> getParticipants(@PathVariable Long roomId){
        List<ChatRoomParticipantsResponse> responses = chatService.getRoomParticipants(roomId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "유저 프로필 조회", description = "userId를 통해 유저의 상세 프로필 정보와 이미지 URL을 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatUserProfileResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<ApiResponse<ChatUserProfileResponse>> getUserProfile(@PathVariable Long userId) {
        ChatUserProfileResponse response = chatService.getUserProfile(userId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "특정 사용자 차단", description = "대화 상대를 차단합니다. 이미 차단되어 있거나 자기 자신은 차단할 수 없습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "차단 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 또는 이미 차단됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대상 사용자를 찾을 수 없음")
    })
    @PostMapping("/block/{targetUserId}")
    public ResponseEntity<ApiResponse<?>> blockUser(
            @PathVariable @Positive Long targetUserId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        chatService.blockChatUser(targetUserId, principal.getUserId());
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("차단 성공"));
    }
    @Operation(summary = "채팅 내용 신고", description = "채팅방, 사용자, 메시지 내용을 신고합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신고 접수 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본인의 메시지는 신고할 수 없습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "신고 대상 메시지 또는 신고자를 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 신고한 메시지입니다 (중복 신고)")
    })
    @PostMapping("/declaration")
    public ResponseEntity<ApiResponse<Void>> declaration(
            @Valid @RequestBody ChatReportRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long reporterUserId = principal.getUserId();
        chatService.reportChat(reporterUserId, request);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 번역 기능 설정", description = "특정 채팅방의 메시지 번역 기능을 켜거나 끕니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 참여자를 찾을 수 없음")
    })
    @PostMapping("/rooms/{roomId}/translation")
    public ResponseEntity<ApiResponse<Void>> toggleTranslation(
            @PathVariable Long roomId,
            @RequestBody ToggleTranslationRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        chatService.toggleTranslation(roomId, userId, request.translateEnabled());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 알림 설정 변경", description = "특정 채팅방의 알림을 켜거나 끕니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "알림 설정 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 해당 채팅방의 참여자가 아닐 경우")
    })
    @PostMapping("/rooms/{roomId}/notifications-toggle")
    public ResponseEntity<ApiResponse<Void>> toggleChatRoomNotifications(
            @Parameter(description = "설정을 변경할 채팅방의 ID") @PathVariable Long roomId,
            @Valid @RequestBody ToggleNotificationsRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        chatService.toggleChatRoomNotifications(roomId, userId, request.enabled());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 알림 상태 조회", description = "현재 유저의 특정 채팅방 알림 설정 상태(on/off)를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatNotificationStatusResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 참여자가 아님",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/{roomId}/notification-status")
    public ResponseEntity<ApiResponse<ChatNotificationStatusResponse>> getNotificationStatus(
            @Parameter(description = "채팅방 ID") @PathVariable @Positive Long roomId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        ChatNotificationStatusResponse response = chatService.isNotificationsEnabled(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}