package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatRoomService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.ChatErrorDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.ImageErrorCodeDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema; // 추가됨
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "채팅방 관리 API", description = "채팅방 생성, 목록 조회, 검색, 입장/퇴장 등 방(Room) 자체 관리")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class ChatRoomController {

    private final ChatRoomService chatService;
    private final FeatureUsageMetrics featureUsageMetrics;

    @Operation(summary = "1:1 새로운 채팅방 생성", description = "1:1 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            )
    })
    @PostMapping("/rooms/oneToOne")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        ChatRoom room = chatService.createRoom(principal.getUserId(), request.otherUserId());
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(ChatRoomResponse.from(room)));
    }

    @PostMapping("/rooms/oneTone")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createOneRoom(
            @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        ChatRoom room = chatService.createRoom(principal.getUserId(), request.otherUserId());
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(ChatRoomResponse.from(room)));
    }
    @Operation(summary = "그룹 채팅방 생성", description = "새로운 그룹 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "채팅방 생성 성공")
    })
    @PostMapping("/rooms/group")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    @ImageErrorCodeDocs({ImageErrorCode.CHATROOM_IMAGES_ALREADY_EXIST, ImageErrorCode.IMAGE_UPLOAD_FAILED, ImageErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR, })
    public ResponseEntity<ApiResponse<Void>> createGroupChat(
            @Valid @RequestBody CreateGroupChatRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        chatService.createGroupChatRoom( principal.getUserId(), request);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }


    // [수정 2] List 반환 타입 명시 (@ArraySchema 사용)
    @Operation(summary = "자신의 채팅방 리스트 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatRoomSummaryResponse.class)))
            )
    })
    @GetMapping("/rooms")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> getMyChatRooms(@AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        List<ChatRoomSummaryResponse> responses = chatService.getMyAllChatRoomSummaries(userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 나가기")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
    })
    @DeleteMapping("/rooms/{roomId}/leave")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ChatErrorDocs({ChatErrorCode.CHAT_PARTICIPANT_NOT_FOUND})
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId, @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        chatService.leaveRoom(roomId, userId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅 참여")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
    })
    @PostMapping("/rooms/group/{roomId}/join")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND, ChatErrorCode.CHAT_NOT_GROUP, ChatErrorCode.ALREADY_CHAT_PARTICIPANT})
    public ResponseEntity<ApiResponse<Void>> joinGroupChat(@PathVariable Long roomId,
                                                           @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        chatService.joinGroupChat(roomId, userId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // [수정 3] 문서화 누락 보완 (@ApiResponses 추가)
    @Operation(summary = "그룹 채팅 상세 정보 조회", description = "그룹 채팅방의 상세 정보(이름, 오너, 참여자 목록 등)를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = GroupChatDetailResponse.class))),
    })
    @GetMapping("/rooms/group/{roomId}")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public ResponseEntity<ApiResponse<GroupChatDetailResponse>> getGroupChatDetails(@PathVariable Long roomId) {
        GroupChatDetailResponse response = chatService.getGroupChatDetails(roomId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // [수정 4] List 반환 타입 명시
    @Operation(summary = "채팅방 이름 검색", description = "사용자가 참여 중인 채팅방을 이름 키워드로 검색합니다. 1:1, 그룹 채팅 모두 포함됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChatRoomSummaryResponse.class)))
            ),
    })
    @GetMapping("/rooms/search")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> searchRooms(
            @RequestParam("roomName") String roomName, @AuthenticationPrincipal CustomUserDetails principal
    ) {
        List<ChatRoomSummaryResponse> responses = chatService.searchRoomsByRoomName(principal.getUserId(), roomName);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // [수정 5] 문서화 누락 보완 및 List 타입 명시
    @Operation(summary = "그룹 채팅방 검색", description = "채팅방 이름 키워드를 통해 그룹 채팅방을 검색합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = GroupChatSearchResponse.class))))
    })
    @GetMapping("/rooms/group/search")
    public ResponseEntity<ApiResponse<List<GroupChatSearchResponse>>> searchGroupChats(@RequestParam String keyword) {
        List<GroupChatSearchResponse> response = chatService.searchGroupChatRooms(keyword);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // [수정 6] 리턴 타입 불일치 수정 (SearchResponse -> MainResponse) 및 List 타입 명시
    @Operation(summary = "최신 그룹 채팅방 10개 조회", description = "가장 최근에 생성된 그룹 채팅방 10개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = GroupChatMainResponse.class)))
            )
    })
    @GetMapping("/group/latest")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getLatestGroupChats(
            @RequestParam(required = false) Long lastChatRoomId) {
        List<GroupChatMainResponse> response = chatService.getLatestGroupChats(lastChatRoomId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // [수정 7] List 반환 타입 명시
    @Operation(summary = "인기 그룹 채팅방 10개 조회", description = "참여자가 가장 많은 그룹 채팅방 10개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = GroupChatMainResponse.class)))
            )
    })
    @GetMapping("/group/popular")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getPopularGroupChats() {
        List<GroupChatMainResponse> response = chatService.getPopularGroupChats(10);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "그룹 채팅방 추천", description = "추천 가능한(isRecommendable=true) 그룹 채팅방 중, 사용자가 속하지 않은 방을 랜덤으로 1개 추천합니다. 요청은 3시간에 한 번, 사용자가 오늘 더 보지 않겠다 할 시 24시간 후 요청")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRecommendRoomResponse.class))
            ),
    })
    @GetMapping("/rooms/recommend")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ChatErrorDocs({ChatErrorCode.NO_RECOMMENDABLE_ROOM})
    public ResponseEntity<ApiResponse<ChatRecommendRoomResponse>> getRecommendableGroupChatRoom(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        ChatRecommendRoomResponse response = chatService.findRandomRecommendableGroupChatRoom(principal.getUserId());
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "채팅방이 그룹인지 여부 확인", description = "roomId에 해당하는 채팅방이 그룹 채팅방인지(1:1 채팅인지) 확인합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", content = @Content(schema = @Schema(implementation = ChatRoomGroupResponse.class))),
    })
    @GetMapping("/isGroup")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public ResponseEntity<ApiResponse<ChatRoomGroupResponse>> isChatRoomGroup(@RequestParam Long roomId) {
        return ResponseEntity.ok(ApiResponse.success(new ChatRoomGroupResponse(chatService.isChatRoomGroup(roomId))));
    }
}