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
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRoomService {

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
        // 1. 유저 검증 (이 부분은 트랜잭션 롤백과 무관하므로 try 밖이 깔끔합니다)
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(user);

        try {
            // 2. 기존 로직 시도
            List<Long> userIds = Arrays.asList(currentUserId, otherUserId);
            List<ChatRoom> existingRooms = chatRoomRepository.findOneToOneRoomByParticipantIds(userIds);

            if (!existingRooms.isEmpty()) {
                if (existingRooms.size() > 1) {
                    log.warn("중복된 1:1 채팅방 발견. 사용자 ID: {}, {}. 첫 번째 방을 사용합니다.", currentUserId, otherUserId);
                }
                return handleExistingRoom(existingRooms.get(0), currentUserId);
            } else {
                // 여기서 동시에 들어오면 Insert 충돌 발생 가능 -> 예외 발생
                return createNewOneToOneChatRoom(currentUserId, otherUserId);
            }

        } catch (DataIntegrityViolationException e) {
            // 3. 동시성 이슈 발생 시 처리 (이미 방이 만들어진 경우)
            log.warn("1:1 채팅방 생성 중 동시성 충돌 발생. 기존 방을 조회하여 반환합니다. User: {}, Target: {}", currentUserId, otherUserId);

            // 다시 조회해서 기존 방을 리턴 (Retry)
            List<Long> userIds = Arrays.asList(currentUserId, otherUserId);
            return chatRoomRepository.findOneToOneRoomByParticipantIds(userIds)
                    .stream()
                    .findFirst()
                    .map(room -> handleExistingRoom(room, currentUserId)) // 재입장 처리까지 수행
                    .orElseThrow(() -> {
                        log.error("채팅방 생성 충돌 후 재조회 실패. User: {}, Target: {}", currentUserId, otherUserId);
                        return new BusinessException(ChatErrorCode.CHAT_ROOM_CREATION_FAILED);
                    });
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
                            chatParticipantRepository.save(newParticipant);
                        }
                );
    }

    @Transactional
    public boolean leaveRoom(Long roomId, Long userId) {

        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        if (participant.getStatus() == ChatParticipantStatus.LEFT) {
            return true;
        }
        try {
            participant.leave();
        } catch (EntityNotFoundException e) {
            log.warn("⚠️ 존재하지 않는 유저의 나가기 요청 (Data Integrity Issue) - RoomId: {}, UserId: {}", roomId, userId);
            chatParticipantRepository.delete(participant);
            return true;
        }

        // 4. 방이 비었는지 확인 후 삭제
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
        List<GroupChatSearchResponse> resultList = new ArrayList<>();
        for (ChatRoom chatRoom : chatRooms) {

            String roomImageUrl = null;
            var imageOptional = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                    ImageType.CHAT_ROOM, chatRoom.getId());

            if (imageOptional.isPresent()) {
                roomImageUrl = imageOptional.get().getUrl();
            }

            int activeParticipantCount = 0;
            for (ChatParticipant p : chatRoom.getParticipants()) {
                if (p.getStatus() == ChatParticipantStatus.ACTIVE) {
                    activeParticipantCount++;
                }
            }
            resultList.add(GroupChatSearchResponse.from(
                    chatRoom,
                    roomImageUrl,
                    activeParticipantCount
            ));
        }

        return resultList;
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

        List<GroupChatMainResponse> responseList = new ArrayList<>();

        for (ChatRoom chatRoom : latestRooms) {
            GroupChatMainResponse response = toGroupChatMainResponse(chatRoom);
            responseList.add(response);
        }

        return responseList;
    }
    @Transactional
    public List<GroupChatMainResponse> getPopularGroupChats(int limit) {
        List<ChatRoom> popularRooms = chatRoomRepository.findTopByIsGroupTrueOrderByParticipantCountDesc(limit);
        List<GroupChatMainResponse> responseList = new ArrayList<>();
        for (ChatRoom chatRoom : popularRooms) {
            GroupChatMainResponse response = toGroupChatMainResponse(chatRoom);
            responseList.add(response);
        }

        return responseList;
    }

    private ChatRoom handleExistingRoom(ChatRoom room, Long currentUserId) {
        List<ChatParticipant> participants = room.getParticipants();

        participants.stream()
                .filter(p -> p.getUser().getId().equals(currentUserId))
                .findFirst()
                .ifPresent(p -> {
                    if (p.getStatus() == ChatParticipantStatus.LEFT) {
                        p.reJoin();
                    }
                });

        participants.stream()
                .filter(p -> !p.getUser().getId().equals(currentUserId))
                .forEach(p -> {
                    if (p.getStatus() == ChatParticipantStatus.LEFT) {
                        p.reJoin();
                    }
                });

        return room;
    }

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
        String roomImageUrl = null;
        var imageOptional = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.CHAT_ROOM, chatRoom.getId());

        if (imageOptional.isPresent()) {
            roomImageUrl = imageOptional.get().getUrl();
        }

        int activeCount = 0;
        for (ChatParticipant p : chatRoom.getParticipants()) {
            if (p.getStatus() == ChatParticipantStatus.ACTIVE) {
                activeCount++;
            }
        }

        return new GroupChatMainResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                roomImageUrl,
                String.valueOf(activeCount) // int -> String 변환
        );
    }
    private GroupChatMainResponse toGroupChatSearchResponse(ChatRoom chatRoom) {
        return toGroupChatMainResponse(chatRoom);
    }

    class ChatRoomSortData {
        ChatRoom room;
        ChatMessage lastMessage;
        Instant sortTime;

        public ChatRoomSortData(ChatRoom room, ChatMessage lastMessage, Instant sortTime) {
            this.room = room;
            this.lastMessage = lastMessage;
            this.sortTime = sortTime;
        }
    }

    @Transactional(readOnly = true)
    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        // 1. 사용자 검증
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 내가 참여중인(ACTIVE) 채팅방 목록 조회
        List<ChatRoom> rooms = chatRoomRepository.findActiveHumanChatRoomsByUserId(userId, ChatParticipantStatus.ACTIVE);

        if (rooms.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. 마지막 메시지 조회를 위한 Room ID 리스트 추출 (반복문 사용)
        List<Long> roomIds = new ArrayList<>();
        for (ChatRoom room : rooms) {
            roomIds.add(room.getId());
        }

        // 4. 각 방의 마지막 메시지 조회 및 매핑
        List<ChatMessage> lastMessages = chatMessageRepository.findLastMessagesByRoomIds(roomIds);
        Map<Long, ChatMessage> lastMessageMap = new HashMap<>();
        for (ChatMessage msg : lastMessages) {
            lastMessageMap.put(msg.getChatRoom().getId(), msg);
        }

        // 5. 정렬을 위한 데이터 리스트 생성
        List<ChatRoomSortData> sortList = new ArrayList<>();
        for (ChatRoom room : rooms) {
        /*
        todo: 차단로직 다시 추가
         if (!room.getIsGroup()) {
             boolean isBlocked = isBlockedRoom(room, userId);
             if (isBlocked) continue;
         }
        */

            ChatMessage lastMessage = lastMessageMap.get(room.getId());

            // 정렬 기준 시간 설정 (마지막 메시지 시간 or 방 생성 시간)
            Instant sortTime;
            if (lastMessage != null) {
                sortTime = lastMessage.getSentAt();
            } else {
                sortTime = room.getCreatedAt();
            }

            sortList.add(new ChatRoomSortData(room, lastMessage, sortTime));
        }

        // 6. 최신순 정렬 (Comparator 람다 -> 익명 클래스 스타일은 너무 길어지므로, 비교 로직만 깔끔하게 유지)
        Collections.sort(sortList, (o1, o2) -> {
            if (o2.sortTime == null) return -1;
            if (o1.sortTime == null) return 1;
            return o2.sortTime.compareTo(o1.sortTime); // 내림차순
        });

        // 7. 최종 응답 변환 (반복문 사용)
        List<ChatRoomSummaryResponse> responseList = new ArrayList<>();
        for (ChatRoomSortData data : sortList) {
            // 변환 메서드 호출
            ChatRoomSummaryResponse summary = createSummaryResponse(data.room, data.lastMessage, userId);
            responseList.add(summary);
        }

        return responseList;
    }

    private ChatRoomSummaryResponse createSummaryResponse(ChatRoom room, ChatMessage lastMessage, Long userId) {
        // [기본 정보 설정]
        String lastMessageContent = "";
        Instant lastMessageTime = room.getCreatedAt();

        if (lastMessage != null) {
            lastMessageContent = lastMessage.getContent();
            lastMessageTime = lastMessage.getSentAt();
        }

        int unreadCount = countUnreadMessages(room.getId(), userId);

        // [참여자 분석] 반복문 하나로 '활성 인원 카운트'와 '상대방 찾기' 동시 수행
        int activeParticipantCount = 0;
        User opponent = null;

        for (ChatParticipant p : room.getParticipants()) {
            // 1. 활성 상태인 참여자만 카운트 (수정된 부분)
            if (p.getStatus() == ChatParticipantStatus.ACTIVE) {
                activeParticipantCount++;
            }

            // 2. 1:1 채팅일 경우 상대방 찾기 (내가 아닌 유저)
            if (!room.getIsGroup() && !p.getUser().getId().equals(userId)) {
                opponent = p.getUser();
            }
        }

        // [방 이름 및 이미지 결정]
        String roomName = "";
        String roomImageUrl = null;

        if (!room.getIsGroup()) {
            // 1:1 채팅
            if (opponent != null) {
                String lastName = (opponent.getLastName() != null) ? opponent.getLastName() : "";
                String firstName = (opponent.getFirstName() != null) ? opponent.getFirstName() : "";
                roomName = lastName + firstName;
                roomImageUrl = imageService.getUserProfileKey(opponent.getId());
            } else {
                roomName = "Unknown user";
            }
        } else {
            // 그룹 채팅
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
                activeParticipantCount // 수정된 카운트 적용
        );
    }
}