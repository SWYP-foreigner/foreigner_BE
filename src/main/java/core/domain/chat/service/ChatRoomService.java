package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.metrics.SocialChatMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRoomService {

    // 변수명 중복 제거 및 통일 (repo -> repository)
    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final ImageService imageService;
    private final BlockRepository blockRepository;
    private final UserRoleDetectService userRoleDetectService;
    private final SocialChatMetrics socialChatMetrics;

    @Transactional
    public ChatRoom createRoom(Long currentUserId, Long otherUserId) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(user);

        List<Long> userIds = Arrays.asList(currentUserId, otherUserId);
        List<ChatRoom> existingRooms = chatRoomRepository.findOneToOneRoomByParticipantIds(userIds);

        if (!existingRooms.isEmpty()) {
            if (existingRooms.size() > 1) {
                log.warn("중복된 1:1 채팅방 발견. 사용자 ID: {}, {}. 첫 번째 방을 사용합니다.", currentUserId, otherUserId);
            }
            ChatRoom room = existingRooms.get(0);
            return handleExistingRoom(room, currentUserId);
        } else {
            return createNewOneToOneChatRoom(currentUserId, otherUserId);
        }
    }

    @Transactional
    public void createGroupChatRoom(Long userId, CreateGroupChatRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(owner);

        if (request.roomName() == null || request.roomName().isBlank()) {
            throw new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND); // 적절한 에러코드로 수정 권장 (예: INVALID_INPUT)
        }

        ChatRoom newRoom = new ChatRoom(
                true,
                Instant.now(),
                request.roomName().trim(),
                request.description(),
                owner
        );
        ChatRoom savedRoom = chatRoomRepository.save(newRoom);

        ChatParticipant ownerParticipant = new ChatParticipant(savedRoom, owner);
        chatParticipantRepository.save(ownerParticipant);

        if (request.roomImageUrl() != null && !request.roomImageUrl().isBlank()) {
            imageService.saveChatRoomProfileImage(savedRoom.getId(), request.roomImageUrl());
        }
    }

    // --- 채팅방 목록 조회 ---
    @Transactional(readOnly = true)
    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<ChatRoom> rooms = chatRoomRepository.findActiveHumanChatRoomsByUserId(userId, ChatParticipantStatus.ACTIVE);

        return rooms.stream()
                .filter(room -> {
                    if (room.getIsGroup()) {
                        return true;
                    }
                    Instant time = getLastMessageTime(room.getId());
                    log.info("방ID: {}, 시간: {}, 현재시간: {}", room.getId(), time, Instant.now());
                    Optional<User> opponentOpt = room.getParticipants().stream()
                            .map(ChatParticipant::getUser)
                            .filter(u -> !u.getId().equals(userId))
                            .findFirst();

                    if (opponentOpt.isPresent()) {
                        boolean isBlockedByMe = blockRepository.existsBlock(userId, opponentOpt.get().getId());
                        return !isBlockedByMe;
                    }
                    return true;
                })
                // 수정됨: ChatService.ChatRoomWithTime -> ChatRoomWithTime
                .map(room -> new ChatRoomWithTime(room, getLastMessageTime(room.getId())))
                .sorted(Comparator.comparing(
                        ChatRoomWithTime::lastMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(roomWithTime -> {
                    ChatRoom room = roomWithTime.room();
                    Instant lastMessageTime = roomWithTime.lastMessageTime();

                    String lastMessageContent = getLastNonBlockedMessageContent(room.getId(), userId);
                    int unreadCount = countUnreadMessages(room.getId(), userId);
                    int participantCount = room.getParticipants().size();
                    String roomName = "";
                    String roomImageUrl;

                    if (!room.getIsGroup()) {
                        User opponent = room.getParticipants().stream()
                                .map(ChatParticipant::getUser)
                                .filter(u -> !u.getId().equals(userId))
                                .findFirst()
                                .orElse(null);

                        if (opponent != null) {
                            if (opponent.getLastName() != null) roomName += opponent.getLastName();
                            if (opponent.getFirstName() != null) roomName += opponent.getFirstName();
                            roomImageUrl = imageService.getUserProfileKey(opponent.getId());
                        } else {
                            roomName = "Unknown user";
                            roomImageUrl = null;
                        }
                    } else {
                        roomName = room.getRoomName();
                        roomImageUrl = imageService.getRoomImageUrl(room.getId());
                    }

                    return new ChatRoomSummaryResponse(
                            room.getId(),
                            roomName,
                            lastMessageContent,
                            lastMessageTime,
                            roomImageUrl,
                            unreadCount,
                            participantCount
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupChatDetailResponse getGroupChatDetails(Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> activeParticipants = chatRoom.getParticipants().stream()
                .filter(participant -> participant.getStatus() == ChatParticipantStatus.ACTIVE)
                .collect(Collectors.toList());

        String roomImageUrl = imageService.getRoomImageUrl(chatRoomId);

        Long ownerId = chatRoom.getOwner().getId();
        String ownerImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, ownerId)
                .map(Image::getUrl).orElse(null);

        List<String> otherParticipantsImageUrls = activeParticipants.stream()
                .filter(participant -> !participant.getUser().getId().equals(ownerId))
                .map(participant -> imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                                ImageType.USER, participant.getUser().getId())
                        .map(Image::getUrl).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return GroupChatDetailResponse.from(
                chatRoom,
                roomImageUrl,
                activeParticipants.size(),
                otherParticipantsImageUrls,
                ownerImageUrl
        );
    }

    @Transactional
    public void joinGroupChat(Long roomId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(user);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!room.getIsGroup()) {
            throw new BusinessException(ChatErrorCode.CHAT_NOT_GROUP);
        }

        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .ifPresentOrElse(
                        participant -> {
                            if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
                                throw new BusinessException(ChatErrorCode.ALREADY_CHAT_PARTICIPANT);
                            } else {
                                participant.reJoin();
                            }
                        },
                        () -> {
                            ChatParticipant newParticipant = new ChatParticipant(room, user);
                            room.addParticipant(newParticipant);
                            chatParticipantRepository.save(newParticipant);
                        }
                );
    }

    @Transactional
    public boolean leaveRoom(Long roomId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(user);

        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserIdAndStatusIsNot(roomId, userId, ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        participant.leave();
        deleteRoomIfEmpty(roomId);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isChatRoomGroup(Long roomId) {
        return chatRoomRepository.findById(roomId)
                .map(ChatRoom::getIsGroup)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public List<ChatRoomSummaryResponse> searchRoomsByRoomName(Long userId, String roomName) {
        List<ChatRoom> rooms = chatParticipantRepository.findChatRoomsByUserIdAndRoomName(userId, roomName);
        List<ChatRoomSummaryResponse> summaries = new ArrayList<>();
        for (ChatRoom room : rooms) {
            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(room.getId(), userId);
            summaries.add(summary);
        }
        return summaries;
    }

    public List<GroupChatSearchResponse> searchGroupChatRooms(String keyword) {
        List<ChatRoom> chatRooms = chatRoomRepository.findGroupChatRoomsByKeyword(keyword);
        return chatRooms.stream()
                .map(chatRoom -> {
                    String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                            ImageType.CHAT_ROOM, chatRoom.getId()
                    ).map(Image::getUrl).orElse(null);
                    int participantCount = chatRoom.getParticipants().size();
                    return GroupChatSearchResponse.from(chatRoom, roomImageUrl, participantCount);
                })
                .collect(Collectors.toList());
    }

    public ChatRecommendRoomResponse findRandomRecommendableGroupChatRoom(Long userId) {
        List<Long> recommendableIds = chatRoomRepository.findRecommendableGroupChatRoomIdsNotJoinedByUserId(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(user);

        if (recommendableIds.isEmpty()) return null;
        log.info("후보 ID 목록: {}", recommendableIds);
        int randomIndex = new Random().nextInt(recommendableIds.size());
        log.info("선택된 인덱스: {}, 선택된 ID: {}", randomIndex, recommendableIds.get(randomIndex));
        Long randomRoomId = recommendableIds.get(randomIndex);

        ChatRoom room = chatRoomRepository.findById(randomRoomId).orElse(null);
        if (room == null) throw new BusinessException(ChatErrorCode.NO_MORE_RECOMMENDABLE_ROOM);
        String imageUrl = imageRepository.findTopByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, randomRoomId)
                .map(Image::getUrl).orElse(null);
        return ChatRecommendRoomResponse.of(room, imageUrl);
    }

    @Transactional
    public List<GroupChatMainResponse> getLatestGroupChats(Long lastChatRoomId) {
        List<ChatRoom> latestRooms;
        if (lastChatRoomId == null) {
            latestRooms = chatRoomRepository.findTop10ByIsGroupTrueOrderByCreatedAtDesc();
        } else {
            latestRooms = chatRoomRepository.findTop10ByIsGroupTrueAndIdLessThanOrderByCreatedAtDesc(lastChatRoomId);
        }
        return latestRooms.stream().map(this::toGroupChatMainResponse).collect(Collectors.toList());
    }

    @Transactional
    public List<GroupChatMainResponse> getPopularGroupChats(int limit) {
        List<ChatRoom> popularRooms = chatRoomRepository.findTopByIsGroupTrueOrderByParticipantCountDesc(limit);
        return popularRooms.stream().map(this::toGroupChatSearchResponse).collect(Collectors.toList());
    }

    private ChatRoom handleExistingRoom(ChatRoom room, Long currentUserId) {
        Optional<ChatParticipant> currentParticipant = room.getParticipants().stream()
                .filter(p -> p.getUser().getId().equals(currentUserId))
                .findFirst();
        if (currentParticipant.isPresent() && currentParticipant.get().getStatus() == ChatParticipantStatus.LEFT) {
            currentParticipant.get().reJoin();
        }
        return room;
    }

    // --- Private Helpers (내부 로직) ---

    private ChatRoom createNewOneToOneChatRoom(Long userId1, Long userId2) {
        User currentUser = userRepository.findById(userId1)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        User otherUser = userRepository.findById(userId2)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        ChatRoom newRoom = new ChatRoom(false, Instant.now(), "1:1 채팅방");
        newRoom.addParticipant(new ChatParticipant(newRoom, currentUser));
        newRoom.addParticipant(new ChatParticipant(newRoom, otherUser));

        socialChatMetrics.recordInterest(countryOf(currentUser), countryOf(otherUser), "chat_room");
        return chatRoomRepository.save(newRoom);
    }

    private String countryOf(User u) {
        return Optional.ofNullable(u.getCountry()).orElse(null);
    }

    private void deleteRoomIfEmpty(Long roomId) {
        if (chatParticipantRepository.countByChatRoomIdAndStatus(roomId, ChatParticipantStatus.ACTIVE) == 0) {
            chatMessageRepository.deleteByChatRoomId(roomId);
            chatRoomRepository.deleteById(roomId);
            imageService.deleteChatRoomProfileImage(roomId);
        }
    }

    // Missing Method Implementation (누락되었던 메서드 추가)
    private Instant getLastMessageTime(Long roomId) {
        return chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId)
                .map(ChatMessage::getSentAt)
                .orElse(null);
    }

    private String getLastNonBlockedMessageContent(Long roomId, Long userId) {
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<User> blockedUsers = blockRepository.findByUser(currentUser)
                .stream()
                .map(BlockUser::getBlocked)
                .toList();

        Optional<ChatMessage> lastMessage;
        if (blockedUsers.isEmpty()) {
            lastMessage = chatMessageRepository.findFirstByChatRoomIdOrderBySentAtDesc(roomId);
        } else {
            lastMessage = chatMessageRepository.findFirstByChatRoomIdAndSenderNotInOrderBySentAtDesc(roomId, blockedUsers);
        }
        return lastMessage.map(ChatMessage::getContent).orElse("새로운 메시지가 없습니다.");
    }

    private int countUnreadMessages(Long roomId, Long userId) {
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId)
                .orElse(0L);
        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
    }

    private ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatMessage lastMessage = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId).orElse(null);
        String lastMessageContent = (lastMessage != null) ? lastMessage.getContent() : "대화를 시작해보세요.";
        Instant lastMessageTime = (lastMessage != null) ? lastMessage.getSentAt() : room.getCreatedAt();
        int unreadCount = countUnreadMessages(roomId, forUserId);
        String roomName;
        String roomImageUrl;

        List<ChatParticipant> participants = room.getParticipants();
        int participantCount = participants.size();

        if (!room.getIsGroup()) {
            User opponent = participants.stream()
                    .map(ChatParticipant::getUser)
                    .filter(user -> !user.getId().equals(forUserId))
                    .findFirst().orElse(null);
            if (opponent == null) {
                roomName = "Unknown user";
                roomImageUrl = null;
            } else {
                roomName = opponent.getFirstName() + " " + opponent.getLastName();
                roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                        .map(Image::getUrl).orElse(null);
            }
        } else {
            roomName = room.getRoomName();
            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, room.getId())
                    .map(Image::getUrl).orElse(null);
        }

        return new ChatRoomSummaryResponse(room.getId(), roomName, lastMessageContent, lastMessageTime, roomImageUrl, unreadCount, participantCount);
    }

    private GroupChatMainResponse toGroupChatMainResponse(ChatRoom chatRoom) {
        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoom.getId())
                .map(Image::getUrl).orElse(null);
        String userCount = String.valueOf(chatRoom.getParticipants().size());
        return new GroupChatMainResponse(chatRoom.getId(), chatRoom.getRoomName(), chatRoom.getDescription(), roomImageUrl, userCount);
    }

    private GroupChatMainResponse toGroupChatSearchResponse(ChatRoom chatRoom) {
        return toGroupChatMainResponse(chatRoom); // 로직이 동일하여 재사용
    }

    // 내부 DTO (Record)
    private record ChatRoomWithTime(ChatRoom room, Instant lastMessageTime) {
    }
}