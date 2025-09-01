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
import core.global.config.CustomUserDetails;
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
import org.springframework.security.core.context.SecurityContextHolder;
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

    @Operation(summary = "1:1 새로운 채팅방 생성", description = "1:1 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/rooms/oneTone")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @RequestBody  Long otherUserId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        ChatRoom room = chatService.createRoom(userId, otherUserId);
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
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>>ChatRooms() {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatRoomSummaryResponse> responses = chatService.getMyAllChatRoomSummaries(userId);
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
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.leaveRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }


    @Operation(summary = "채팅방 메시지 조회 (무한 스크롤 위로 스크롤올릴때 호출하는 api )")
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
            @RequestParam(required = false) Long lastMessageId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();

        List<ChatMessageResponse> responses = chatService.getMessages(roomId, userId, lastMessageId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    @Operation(summary = "첫 채팅방 메시지 조회", description = "채팅방에 처음 입장 시 가장 최근 메시지 50개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatMessageResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 유저를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/{roomId}/first_messages")
    public ResponseEntity<ApiResponse<List<ChatMessageFirstResponse>>> getFirstMessages(
            @PathVariable Long roomId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatMessageFirstResponse> responses = chatService.getFirstMessages(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

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
    public ResponseEntity<ApiResponse<List<ChatRoomParticipantsResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatRoomParticipantsResponse> responses = chatService.getRoomParticipants(roomId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "유저 차단")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    @PostMapping("/users/block/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable Long targetUserId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        return ResponseEntity.ok(ApiResponse.success(null));
    }


    @Operation(summary = "그룹 채팅 참여")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 참여 중인 채팅방이거나, 그룹 채팅방이 아닐 경우"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저")
    })
    @PostMapping("/rooms/group/{roomId}/join")
    public ResponseEntity<ApiResponse<Void>> joinGroupChat(@PathVariable Long roomId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();

        chatService.joinGroupChat(roomId, userId);
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
            @RequestParam String keyword
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();

        List<ChatMessageResponse> responses = chatService.searchMessages(roomId, userId, keyword);

        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    @Operation(summary = "그룹 채팅 상세 정보 조회", description = "그룹 채팅방의 상세 정보(이름, 오너, 참여자 목록 등)를 조회합니다.")
    @GetMapping("/rooms/group/{roomId}")
    public ResponseEntity<ApiResponse<GroupChatDetailResponse>> getGroupChatDetails(
            @PathVariable Long roomId) {
        GroupChatDetailResponse response = chatService.getGroupChatDetails(roomId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    @Operation(summary = "그룹 채팅방 검색", description = "채팅방 이름 키워드를 통해 그룹 채팅방을 검색합니다.")
    @GetMapping("/rooms/group/search")
    public ResponseEntity<ApiResponse<List<GroupChatSearchResponse>>> searchGroupChats(@RequestParam String keyword) {
        List<GroupChatSearchResponse> response = chatService.searchGroupChatRooms(keyword);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "채팅방 이름 검색", description = "사용자가 참여 중인 채팅방을 이름 키워드로 검색합니다. 1:1, 그룹 채팅 모두 포함됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomSummaryResponse.class))
            ),
    })
    @GetMapping("/rooms/search")
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> searchRooms(
            @RequestParam("roomName") String roomName
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<ChatRoomSummaryResponse> responses = chatService.searchRoomsByRoomName(principal.getUserId(), roomName);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "최신 그룹 채팅방 10개 조회", description = "가장 최근에 생성된 그룹 채팅방 10개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = GroupChatSearchResponse.class))
            )
    })
    @GetMapping("/group/latest")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getLatestGroupChats(
            @RequestParam(required = false) Long lastChatRoomId) {
        List<GroupChatMainResponse> response = chatService.getLatestGroupChats(lastChatRoomId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }


    @Operation(summary = "인기 그룹 채팅방 10개 조회", description = "참여자가 가장 많은 그룹 채팅방 10개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = GroupChatSearchResponse.class))
            )
    })
    @GetMapping("/group/popular")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getPopularGroupChats() {
        List<GroupChatMainResponse> response = chatService.getPopularGroupChats(10);
        return ResponseEntity.ok(ApiResponse.success(response));
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
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    // ChatController.java

    @Operation(summary = "채팅방 번역 기능 설정", description = "특정 채팅방의 메시지 번역 기능을 켜거나 끕니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 참여자를 찾을 수 없음")
    })
    @PostMapping("/rooms/{roomId}/translation")
    public ResponseEntity<ApiResponse<Void>> toggleTranslation(
            @PathVariable Long roomId,
            @RequestBody ToggleTranslationRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();

        chatService.toggleTranslation(roomId, userId, request.translateEnabled());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}