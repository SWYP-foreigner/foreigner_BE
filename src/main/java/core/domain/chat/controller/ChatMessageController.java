package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.service.ChatMessageService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.ChatErrorDocs;
import core.global.docs.annotations.CommonErrorCodeDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema; // 추가됨
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "채팅 메시지 API", description = "메시지 조회, 검색, 파일 업로드 등 메시지 콘텐츠 관리")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class ChatMessageController {

    private final ChatMessageService chatService;
    private final FeatureUsageMetrics featureUsageMetrics;

    // [수정 1] List 반환 명시 (@ArraySchema) 및 설명 문구 수정
    @Operation(summary = "채팅방 메시지 조회 (과거 내역 무한 스크롤)", description = "채팅방에서 위로 스크롤하여 과거 메시지를 로딩할 때 호출합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatMessageResponse.class)))
            ),
    })
    @GetMapping("/rooms/{roomId}/messages")
    @UserErrorDocs({UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.getMessages(roomId, userId, lastMessageId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }


    @Operation(summary = "메시지 키워드 검색", description = "메시지 내용을 키워드로 검색합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatMessageResponse.class)))
            ),
    })
    @GetMapping("/search")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> searchMessages(
            @RequestParam Long roomId,
            @RequestParam String keyword,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.searchMessages(roomId, userId, keyword);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // [수정 4] List 반환 명시 (@ArraySchema)
    @Operation(summary = "특정 메시지 주변의 채팅 내용 조회", description = "검색 등에서 특정 메시지로 바로 이동할 때 사용합니다. 해당 메시지 기준 이전 20개, 이후 20개의 메시지를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatMessageResponse.class)))
            ),
    })
    @GetMapping("/rooms/{roomId}/messages/around")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessagesAround(
            @PathVariable Long roomId,
            @RequestParam Long messageId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.getMessagesAround(roomId, userId, messageId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방의 모든 메시지를 읽음 처리", description = "해당 채팅방(roomId)의 모든 메시지를 현재 사용자 기준으로 읽음 처리합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "읽음 처리 완료"),
    })
    @PostMapping("/rooms/{roomId}/read-all")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@PathVariable Long roomId,
                                                           @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        chatService.markAllMessagesAsReadInRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // [수정 5] 응답 스키마(PresignedUrlResponse) 명시
    @Operation(summary = "채팅 미디어 Presigned URL 발급",
            description = """
           지정된 채팅방(chatroomId)에 미디어(사진/동영상)를 업로드하기 위한 URL을 발급합니다.
           
           - **IMAGE**: `presignedUrl` 하나만 반환됩니다.
           - **VIDEO**: `presignedUrl`(영상용)과 `thumbnailUrl`(썸네일용) 두 가지가 반환됩니다.
           
           클라이언트는 반환된 URL에 각각 PUT 요청으로 파일을 업로드한 뒤, 
           `fileKey`와 `thumbnailKey`를 사용하여 소켓 메시지를 전송해야 합니다.
           """)
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Presigned URL 발급 성공",
                    content = @Content(schema = @Schema(implementation = PresignedUrlResponse.class))),
    })
    @PostMapping("/presigned-url/chat/{chatroomId}")
    @ChatErrorDocs({ChatErrorCode.NOT_CHAT_PARTICIPANT})
    public ResponseEntity<ApiResponse<PresignedUrlResponse>> getChatPresignedUrl(
            @PathVariable Long chatroomId,
            @RequestBody ChatPresignedUrlRequest request) {

        // fileType 추가 전달
        PresignedUrlResponse response = chatService.generateChatPresignedUrl(
                chatroomId,
                request.fileName(),
                request.fileType()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}