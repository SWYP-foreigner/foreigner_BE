package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatService;
import core.domain.chat.service.ForbiddenWordService;
import core.domain.chat.service.TranslationService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.ApiResponse;

import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "채팅 API", description = "1:1 채팅, 그룹 채팅, 메시지 검색/삭제 등 채팅 기능 API")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chatService;
    private final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TranslationService translationService;
    public ChatController(ChatService chatService, UserRepository userRepository, ChatRoomRepository chatRoomRepository, ChatParticipantRepository chatParticipantRepository, ChatMessageRepository chatMessageRepository, TranslationService translationService) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.translationService = translationService;
    }

    @Operation(summary = "새로운 채팅방 생성", description = "1:1 또는 그룹 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            Long creatorId, @RequestBody  List<Long> participantIds
    ) {
        ChatRoom room = chatService.createRoom(creatorId, participantIds);
        ChatRoomResponse response = ChatRoomResponse.from(room);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "자신의 채팅방 리스트 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            )
    })
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms(Long userId) {
        List<ChatRoom> rooms = chatService.getMyChatRooms(userId);
        List<ChatRoomResponse> responses = rooms.stream()
                .map(ChatRoomResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 나가기")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @DeleteMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId, @RequestParam Long userId) {
        chatService.leaveRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 삭제")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        chatService.forceDeleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 메시지 조회 (무한 스크롤)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatMessageResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId,
            @RequestParam Long userId,
            @RequestParam(required = false, defaultValue = "false") boolean translate
    ) {
        List<ChatMessageResponse> responses = chatService.getMessages(roomId, userId, lastMessageId, translate);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅 참여자 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatParticipantResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatParticipantResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatParticipant> participants = chatService.getParticipants(roomId);

        List<ChatParticipantResponse> responses = participants.stream()
                .map(p -> new ChatParticipantResponse(
                        p.getId(),
                        p.getUser().getId(),
                        p.getUser().getLastName(),
                        p.getJoinedAt(),
                        p.getLastLeftAt(),
                        p.getLastReadMessageId(),
                        p.getStatus()
                )).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "유저 차단")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PostMapping("/users/block/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable Long targetUserId, @RequestParam Long userId) {
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "메시지 삭제")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "존재하지 않는 메시지",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(@PathVariable Long roomId, @PathVariable String messageId) {
        boolean deleted = chatService.deleteMessage(Long.valueOf(messageId));
        if (!deleted) {
            log.warn("메시지 삭제 실패: messageId={}", messageId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("존재하지 않는 메시지입니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅방 재참여")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 참여 중인 채팅방"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/rooms/{roomId}/rejoin")
    public ResponseEntity<ApiResponse<Void>> rejoinChatRoom(@PathVariable Long roomId, @RequestParam Long userId) {
        chatService.rejoinRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅 참여자 추가 및 초대")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<Void>> addParticipants(@PathVariable Long roomId, @RequestBody List<Long> userIds) {
        chatService.addParticipants(roomId, userIds);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "메시지 키워드 검색", description = "메시지 내용을 키워드로 검색합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatMessageResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방",
                    content = @Content(schema = @Schema(implementation = Object.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> searchMessages(
            @RequestParam Long roomId,
            @RequestParam Long userId,
            @RequestParam String search,
            @RequestParam(required = false, defaultValue = "false") boolean translate
    ) {
        List<ChatMessage> messages = chatService.searchMessages(roomId, userId, search);

        if (translate) {
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            String targetLanguage = user.getLanguage();

            if (targetLanguage != null && !targetLanguage.isEmpty()) {
                List<String> originalContents = messages.stream().map(ChatMessage::getContent).collect(Collectors.toList());
                List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

                List<ChatMessageResponse> responses = messages.stream()
                        .map(message -> {
                            int index = messages.indexOf(message);
                            String translatedContent = translatedContents.get(index);
                            return new ChatMessageResponse(
                                    message.getId(),
                                    message.getChatRoom().getId(),
                                    message.getSender().getId(),
                                    translatedContent,
                                    message.getSentAt(),
                                    message.getContent()
                            );
                        }).collect(Collectors.toList());
                return ResponseEntity.ok(ApiResponse.success(responses));
            }
        }

        List<ChatMessageResponse> response = messages.stream()
                .map(ChatMessageResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

}