package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatRoomService;
import core.domain.chat.service.ChatService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import core.global.metrics.FeatureUsageMetrics;
import io.swagger.v3.oas.annotations.Operation;
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
public class ChatRoomController {

    private final ChatRoomService chatService;
    private final FeatureUsageMetrics featureUsageMetrics;

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
            @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        ChatRoom room = chatService.createRoom(userId, request.otherUserId());
        ChatRoomResponse response = ChatRoomResponse.from(room);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "그룹 채팅방 생성", description = "새로운 그룹 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "채팅방 생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않은 요청 데이터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    })
    @PostMapping("/rooms/group")
    public ResponseEntity<ApiResponse<Void>> createGroupChat(
            @Valid @RequestBody CreateGroupChatRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long userId = principal.getUserId();
        chatService.createGroupChatRoom(userId, request);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }

    @Operation(summary = "자신의 채팅방 리스트 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomSummaryResponse.class))
            )
    })
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> getMyChatRooms(@AuthenticationPrincipal CustomUserDetails principal) {
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
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId, @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        chatService.leaveRoom(roomId, userId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅 참여")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 참여 중인 채팅방이거나, 그룹 채팅방이 아닐 경우"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저")
    })
    @PostMapping("/rooms/group/{roomId}/join")
    public ResponseEntity<ApiResponse<Void>> joinGroupChat(@PathVariable Long roomId,
                                                           @AuthenticationPrincipal CustomUserDetails principal) {
        Long userId = principal.getUserId();
        chatService.joinGroupChat(roomId, userId);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅 상세 정보 조회", description = "그룹 채팅방의 상세 정보(이름, 오너, 참여자 목록 등)를 조회합니다.")
    @GetMapping("/rooms/group/{roomId}")
    public ResponseEntity<ApiResponse<GroupChatDetailResponse>> getGroupChatDetails(@PathVariable Long roomId) {
        GroupChatDetailResponse response = chatService.getGroupChatDetails(roomId);
        featureUsageMetrics.recordChatUsage();
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
            @RequestParam("roomName") String roomName, @AuthenticationPrincipal CustomUserDetails principal
    ) {
        List<ChatRoomSummaryResponse> responses = chatService.searchRoomsByRoomName(principal.getUserId(), roomName);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "그룹 채팅방 검색", description = "채팅방 이름 키워드를 통해 그룹 채팅방을 검색합니다.")
    @GetMapping("/rooms/group/search")
    public ResponseEntity<ApiResponse<List<GroupChatSearchResponse>>> searchGroupChats(@RequestParam String keyword) {
        List<GroupChatSearchResponse> response = chatService.searchGroupChatRooms(keyword);
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
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
        featureUsageMetrics.recordChatUsage();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "인기 그룹 채팅방 10개 조회", description = "참여자가 가장 많은 그룹 채팅방 10개를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = GroupChatMainResponse.class))
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "더 이상 추천 가능한 채팅방 없음",
                    content = @Content(schema = @Schema(implementation = Object.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "428", description = "프로필 세팅 미완료한 유저",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @GetMapping("/rooms/recommend")
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없음")
    })
    @GetMapping("/isGroup")
    public ResponseEntity<ApiResponse<ChatRoomGroupResponse>> isChatRoomGroup(@RequestParam Long roomId) {
        boolean isGroup = chatService.isChatRoomGroup(roomId);
        ChatRoomGroupResponse response = new ChatRoomGroupResponse(isGroup);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}