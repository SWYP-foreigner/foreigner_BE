package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.service.ChatMemberService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.ChatErrorDocs;
import core.global.docs.annotations.CommunityErrorDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema; // 추가됨
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@Slf4j
@Tag(name = "채팅 멤버 및 설정 API", description = "참여자 조회, 신고/차단, 알림/번역 설정 등")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class ChatMemberController {

    private final ChatMemberService chatService;
    private final FeatureUsageMetrics featureUsageMetrics;

    // [수정 1] List 반환 명시 (@ArraySchema)
    @Operation(summary = "채팅 참여자 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatRoomParticipantsResponse.class)))
            ),
    })
    @GetMapping("/rooms/{roomId}/participants")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public ResponseEntity<ApiResponse<List<ChatRoomParticipantsResponse>>> getParticipants(@PathVariable Long roomId){
        List<ChatRoomParticipantsResponse> responses = chatService.getRoomParticipants(roomId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }


    @Operation(summary = "특정 사용자 차단", description = "대화 상대를 차단합니다. 이미 차단되어 있거나 자기 자신은 차단할 수 없습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "차단 성공"),
    })
    @PostMapping("/block/{targetUserId}")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED, UserErrorCode.CANNOT_BLOCK, UserErrorCode.ALREADY_BLOCKED})
    public ResponseEntity<ApiResponse<String>> blockUser(
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
    })
    @PostMapping("/declaration")
    @ChatErrorDocs({ChatErrorCode.DUPLICATE_REPORT,ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.CANNOT_REPORT_SELF })
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
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
    })
    @PostMapping("/rooms/{roomId}/translation")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
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
    })
    @PostMapping("/rooms/{roomId}/notifications-toggle")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
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
    })
    @GetMapping("/rooms/{roomId}/notification-status")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    public ResponseEntity<ApiResponse<ChatNotificationStatusResponse>> getNotificationStatus(
            @Parameter(description = "채팅방 ID") @PathVariable @Positive Long roomId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        ChatNotificationStatusResponse response = chatService.isNotificationsEnabled(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}