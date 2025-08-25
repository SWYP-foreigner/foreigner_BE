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
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * @author 김승환
     * 새로운 채팅방을 생성합니다.
     * 그룹 채팅, 1:1 채팅 모두 이 엔드포인트를 사용합니다.
     *
     * @param creatorId      채팅방을 생성하는 사용자의 ID.
     * @param participantIds 채팅방에 초대할 사용자 ID 목록.
     * @return 생성된 채팅방 정보를 담은 응답 (ChatRoomResponse).
     */
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            Long creatorId, @RequestBody  List<Long> participantIds
    ) {
        ChatRoom room = chatService.createRoom(creatorId, participantIds);
        ChatRoomResponse response = ChatRoomResponse.from(room);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     *  @author 김승환
     * 현재 로그인된 사용자가 참여하고 있는 채팅방 목록을 조회합니다.
     *
     * @param userId 채팅방 목록을 조회할 사용자의 ID.
     * @return 사용자가 참여하고 있는 채팅방 목록을 담은 응답 (List<ChatRoomResponse>).
     */
    @Operation(summary = "자신의 채팅방 리스트 조회")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms(Long userId) {
        List<ChatRoom> rooms = chatService.getMyChatRooms(userId);
        List<ChatRoomResponse> responses = rooms.stream()
                .map(ChatRoomResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    /**
     *  @author 김승환
     * @apiNote 사용자가 채팅방을 나갑니다.
     * @param roomId 채팅방 ID
     * @return 성공 여부
     */
    @DeleteMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId, @RequestParam Long userId) {
        chatService.leaveRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     *  @author 김승환
     * @apiNote 커뮤니티로 만들어진 모임 채팅방은 활동이 끝나면 삭제를 원할 시 삭제.
     * @param roomId 삭제할 채팅방 ID
     * @return 성공 여부
     */
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        chatService.forceDeleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }


    /**
     * @apiNote 채팅방 메시지를 무한 스크롤로 조회하고, 선택적으로 번역된 메시지를 반환합니다.
     *
     * @param roomId          채팅방 ID
     * @param userId          조회하는 사용자의 ID.
     * @param lastMessageId   마지막으로 조회된 메시지 ID (무한 스크롤용).
     * @param translate       메시지 번역 요청 여부 (기본값: false)
     * @return 메시지 목록
     */
    @Operation(summary = "채팅방 메시지 조회 (무한 스크롤)")
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


    /**
     *  @author 김승환
     * @apiNote 그룹 채팅방의 현재 참여자 목록을 조회합니다.
     * 1:1 채팅방에서는 사용하지 않습니다.
     *
     * @param roomId 채팅방 ID
     * @return 채팅방 참여자 목록
     */
    @Operation(summary = "채팅 참여자 조회")
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


    /**
     *  @author 김승환
     * @apiNote 다른 유저를 차단합니다.
     * 차단하면 1:1 채팅방이 보이지 않고, 알림 및 메시지 수신이 차단됩니다.
     *
     * @param userId 차단을 수행하는 사용자 ID (TODO: JWT 토큰에서 추출)
     * @param targetUserId 차단할 대상 사용자 ID
     * @return 성공 여부
     *  TODO: 회의 후 구현예정
     */
    @Operation(summary = "유저 차단")
    @PostMapping("/users/block/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable Long targetUserId, @RequestParam Long userId) {
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "메시지 삭제")
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
    @PostMapping("/rooms/{roomId}/rejoin")
    public ResponseEntity<ApiResponse<Void>> rejoinChatRoom(@PathVariable Long roomId, @RequestParam Long userId) {
        chatService.rejoinRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     *  @author 김승환
     * @apiNote 그룹 채팅방에 새로운 참여자를 추가합니다.
     *
     * @param roomId 채팅방 ID
     * @param userIds 추가할 사용자의 ID 목록
     * @return 성공 여부
     */
    @Operation(summary = "그룹 채팅 참여자 추가 및 초대")
    @PostMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<Void>> addParticipants(@PathVariable Long roomId, @RequestBody List<Long> userIds) {
        chatService.addParticipants(roomId, userIds);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "메시지 키워드 검색 (무겁게 수행)", description = "todo: @RequestParam String search가 한글로 올 시 인코딩 오류로 400을 뱉음")
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