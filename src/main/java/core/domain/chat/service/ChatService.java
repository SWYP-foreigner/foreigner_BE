package core.domain.chat.service;


import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageReadStatus;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageReadStatusRepository;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.repository.ImageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import core.global.image.entity.Image;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private static final int MESSAGE_PAGE_SIZE = 20;
    private static final int MESSAGE_SEARCH_PAGE_SIZE = 20;
    private final ChatMessageReadStatusRepository chatMessageReadStatusRepository;
    private final TranslationService translationService;
    private final ImageRepository imageRepository;
    private final ChatRoomRepository chatRoomRepository;

    public ChatService(ChatRoomRepository chatRoomRepo,
                       ChatParticipantRepository participantRepo, ChatMessageRepository chatMessageRepository,
                       UserRepository userRepository, ChatParticipantRepository chatParticipantRepository,
                       ChatMessageReadStatusRepository chatMessageReadStatusRepository, TranslationService translationService, ImageRepository imageRepository, ChatRoomRepository chatRoomRepository) {
        this.chatRoomRepo = chatRoomRepo;
        this.participantRepo = participantRepo;

        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.chatMessageReadStatusRepository = chatMessageReadStatusRepository;
        this.translationService = translationService;
        this.imageRepository = imageRepository;
        this.chatRoomRepository = chatRoomRepository;
    }
    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<ChatRoom> rooms = chatRoomRepo.findChatRoomsByUserId(userId);

        return rooms.stream().map(room -> {
            String lastMessageContent = getLastMessageContent(room.getId());
            LocalDateTime lastMessageTime = getLastMessageTime(room.getId());
            int unreadCount = countUnreadMessages(room.getId(), userId);
            String lastMessageTimeStr = lastMessageTime != null ? lastMessageTime.format(DateTimeFormatter.ofPattern("HH:mm")) : null;
            String roomName;
            String roomImageUrl;
            int participantCount = room.getParticipants().size();

            if (!room.getGroup()) {
                User opponent = room.getParticipants().stream()
                        .map(ChatParticipant::getUser)
                        .filter(u -> !u.getId().equals(userId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("1:1 채팅방 상대방을 찾을 수 없습니다."));

                roomName = opponent.getLastName();

                roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                        .map(Image::getUrl)
                        .orElse(null);
            } else {
                roomName = room.getRoomName();

                roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, room.getId())
                        .map(Image::getUrl)
                        .orElse(null);
            }

            return new ChatRoomSummaryResponse(
                    room.getId(),
                    roomName,
                    lastMessageContent,
                    lastMessageTimeStr,
                    roomImageUrl,
                    unreadCount,
                    participantCount
            );
        }).toList();
    }
    @Transactional
    public ChatRoom createRoom(Long currentUserId, Long otherUserId) {
        Optional<ChatRoom> existingRoom = chatRoomRepo.findByParticipantIds(currentUserId, otherUserId);
        return existingRoom.map(chatRoom -> handleExistingRoom(chatRoom, currentUserId)).orElseGet(() -> createNewOneToOneChatRoom(currentUserId, otherUserId));
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

    private ChatRoom createNewOneToOneChatRoom(Long userId1, Long userId2) {
        User currentUser = userRepository.findById(userId1)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        User otherUser = userRepository.findById(userId2)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String roomName = otherUser.getFirstName() + " " + otherUser.getLastName();

        ChatRoom newRoom = new ChatRoom(false, Instant.now(), roomName);

        ChatParticipant participant1 = new ChatParticipant(newRoom, currentUser);
        ChatParticipant participant2 = new ChatParticipant(newRoom, otherUser);

        newRoom.addParticipant(participant1);
        newRoom.addParticipant(participant2);

        return chatRoomRepo.save(newRoom);
    }
    /**
     * 사용자가 채팅방을 나갑니다.
     * 1:1 채팅방의 경우, 상대방은 방에 남아있습니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 나가려는 사용자 ID
     * @return 채팅방을 나가는 데 성공했는지 여부
     */
    @Transactional
    public boolean leaveRoom(Long roomId, Long userId) {
        ChatParticipant participant = participantRepo.findByChatRoomIdAndUserIdAndStatusIsNot(roomId, userId, ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        participant.leave();
        deleteRoomIfEmpty(roomId);

        return true;
    }
    /**
     * 채팅방의 모든 참여자가 나갔는지 확인하고, 비어있으면 삭제합니다.
     * 이 메서드는 leaveRoom()에서 호출되어 채팅방 삭제 로직을 분리합니다.
     *
     * @param roomId 확인할 채팅방 ID
     */
    @Transactional
    public void deleteRoomIfEmpty(Long roomId) {
        // 1. 채팅방 조회
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // 2. ACTIVE 상태의 참가자만 카운트
        long remainingActiveParticipants = participantRepo.countByChatRoomIdAndStatus(roomId, ChatParticipantStatus.ACTIVE);

        // 3. 남은 ACTIVE 참가자가 0명일 경우에만 방 삭제
        if (remainingActiveParticipants == 0) {
            chatRoomRepo.delete(room);
        }
    }
    @Transactional
    public void forceDeleteRoom(Long roomId) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        chatRoomRepo.delete(room);
    }

    public List<ChatParticipant> getParticipants(Long roomId) {
        return participantRepo.findByChatRoomId(roomId);
    }
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> participants = chatParticipantRepository.findByChatRoom(chatRoom);
        return participants.stream()
                .map(p -> {
                    String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                                    ImageType.USER, p.getUser().getId())
                            .map(Image::getUrl)
                            .orElse(null);

                    boolean isHost = chatRoom.getOwner() != null && chatRoom.getOwner().getId().equals(p.getUser().getId());

                    return new ChatRoomParticipantsResponse(
                            p.getUser().getId(),
                            p.getUser().getFirstName(),
                            p.getUser().getLastName(),
                            userImageUrl,
                            isHost
                    );
                })
                .collect(Collectors.toList());
    }
    /**
            * @apiNote 채팅방 메시지를 무한 스크롤로 조회하는 핵심 로직입니다.
     * 이 메서드는 항상 ChatMessage 엔티티 목록을 반환합니다.
     *
             * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @return ChatMessage 엔티티 목록
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getRawMessages(Long roomId, Long userId, Long lastMessageId) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        if (participant.getStatus() == ChatParticipantStatus.LEFT && participant.getLastLeftAt() != null) {
            Instant lastLeftAt = participant.getLastLeftAt();

            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfterAndIdBefore(
                        roomId, lastLeftAt, lastMessageId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            } else {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfter(
                        roomId, lastLeftAt,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            }
        } else {
            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndIdBefore(
                        roomId, lastMessageId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            } else {
                return chatMessageRepository.findByChatRoomId(
                        roomId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            }
        }
    }

    /**
     * @apiNote 채팅방 메시지를 조회하고, 번역 요청에 따라 ChatMessageResponse 목록을 반환합니다.
     * 이 메서드가 컨트롤러에서 호출되는 주된 엔드포인트가 됩니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @param translate 메시지 번역 요청 여부
     * @return ChatMessageResponse 목록
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(
            Long roomId,
            Long userId,
            Long lastMessageId,
            boolean translate
    ) {
        List<ChatMessage> messages = getRawMessages(roomId, userId, lastMessageId);

        if (translate) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            String targetLanguage = user.getLanguage();
            if (targetLanguage == null || targetLanguage.isEmpty()) {
                return messages.stream()
                        .map(ChatMessageResponse::fromEntity)
                        .toList();
            }

            List<String> originalContents = messages.stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            return messages.stream()
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
                    }).toList();
        } else {
            return messages.stream()
                    .map(message -> new ChatMessageResponse(
                            message.getId(),
                            message.getChatRoom().getId(),
                            message.getSender().getId(),
                            message.getContent(),
                            message.getSentAt(),
                            null
                    )).toList();
        }
    }


    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatParticipant senderParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        if (senderParticipant.getStatus() == ChatParticipantStatus.LEFT) {
            senderParticipant.reJoin();
        }

        if (Boolean.FALSE.equals(room.getGroup())) {
            List<ChatParticipant> participants = chatParticipantRepository.findByChatRoomId(roomId);
            for (ChatParticipant participant : participants) {
                if (!participant.getUser().getId().equals(senderId) && participant.getStatus() == ChatParticipantStatus.LEFT) {
                    participant.reJoin();
                }
            }
        }

        ChatMessage message = new ChatMessage(room, sender, content);
        return chatMessageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> searchMessages(Long roomId, Long userId, String searchKeyword) {
        ChatParticipant participant = participantRepo.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        Pageable pageable = PageRequest.of(0, MESSAGE_SEARCH_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "sentAt"));
        return chatMessageRepository.findByChatRoomIdAndContentContaining(
                roomId, searchKeyword, pageable);
    }

    @Transactional
    public boolean deleteMessage(Long messageId) {
        if (!chatMessageRepository.existsById(messageId)) {
            return false;
        }
        chatMessageRepository.deleteById(messageId);
        return true;
    }
    /**
     * @apiNote 메시지 읽음 상태를 업데이트합니다.
     * 그룹 채팅에서 '누가 읽었는지'를 관리하는 로직입니다.
     *
     * @param roomId 메시지를 읽은 채팅방 ID
     * @param readerId 메시지를 읽은 사용자 ID
     * @param lastReadMessageId 마지막으로 읽은 메시지 ID
     */
    @Transactional
    public void markMessagesAsRead(Long roomId, Long readerId, Long lastReadMessageId) {
        User reader = userRepository.findById(readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        List<ChatMessage> unreadMessages = chatMessageRepository.findByChatRoomIdAndIdGreaterThan(roomId, lastReadMessageId);

        for (ChatMessage msg : unreadMessages) {
            boolean alreadyRead = chatMessageReadStatusRepository.existsByChatMessageAndReader(msg, reader);

            if (!alreadyRead) {
                ChatMessageReadStatus readStatus = new ChatMessageReadStatus(msg, reader, Instant.now());
                chatMessageReadStatusRepository.save(readStatus);
            }
        }

        readerParticipant.setLastReadMessageId(lastReadMessageId);
    }

    public String getLastMessageContent(Long roomId) {
        ChatMessage last = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);
        return (last != null) ? last.getContent() : null;
    }

    public LocalDateTime getLastMessageTime(Long roomId) {
        ChatMessage last = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);
        if (last != null && last.getSentAt() != null) {
            return last.getSentAt().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        return null;
    }
    public ChatRoom getChatRoomById(Long roomId) {
        return chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public GroupChatDetailResponse getGroupChatDetails(Long roomId) {
        ChatRoom chatRoom = chatRoomRepo.findByIdWithParticipants(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!chatRoom.getGroup()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_GROUP);
        }

        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.CHAT_ROOM, chatRoom.getId()
        ).map(Image::getUrl).orElse(null);

        List<String> participants_image_url = chatRoom.getParticipants().stream()
                .map(participant -> imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                        ImageType.USER, participant.getUser().getId()
                ).map(Image::getUrl).orElse(null))
                .collect(Collectors.toList());

        int participantCount = chatRoom.getParticipants().size();

        return GroupChatDetailResponse.from(
                chatRoom,
                roomImageUrl,
                participantCount,
                participants_image_url
        );
    }

    /**
     * 현재 사용자를 지정된 그룹 채팅방에 추가합니다.
     *
     * @param roomId 그룹 채팅방 ID
     * @param userId 참여를 요청하는 사용자의 ID
     */
    public void joinGroupChat(Long roomId, Long userId) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!room.getGroup()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_GROUP);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        /* autor:김승환  사용자가 이미 참여자인지 확인
        ChatParticipantStatus.LEFT 상태인 경우 다시 참여할 수 있도록 처리*/
        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .ifPresentOrElse(participant -> {
                    if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
                        throw new BusinessException(ErrorCode.ALREADY_CHAT_PARTICIPANT);
                    } else {
                        participant.reJoin();
                    }
                }, () -> {
                    ChatParticipant newParticipant = new ChatParticipant(room, user);
                    chatParticipantRepository.save(newParticipant);
                });
    }

    /**
     * 그룹 채팅방을 이름 키워드로 검색합니다.
     * @param keyword 검색 키워드
     * @return 검색 결과 DTO 목록
     */
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

    public List<ChatRoomSummaryResponse> searchRoomsByRoomName(Long userId, String roomName) {
        List<ChatRoom> rooms = chatParticipantRepository.findChatRoomsByUserIdAndRoomName(userId, roomName);

        return rooms.stream()
                .map(room -> createChatRoomSummary(room, userId))
                .collect(Collectors.toList());
    }

    private ChatRoomSummaryResponse createChatRoomSummary(ChatRoom room, Long userId) {

        Optional<ChatMessage> lastMessageOpt = chatMessageRepository.findFirstByChatRoomIdOrderBySentAtDesc(room.getId());
        String lastMessageContent = lastMessageOpt.map(ChatMessage::getContent).orElse(null);
        LocalDateTime lastMessageTime = lastMessageOpt
                .map(ChatMessage::getSentAt)
                .map(instant -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
                .orElse(null);


        int unreadCount = countUnreadMessages(room.getId(), userId);

        return ChatRoomSummaryResponse.from(
                room,
                userId,
                lastMessageContent,
                lastMessageTime,
                unreadCount,
                imageRepository
        );
    }

    public int countUnreadMessages(Long roomId, Long userId) {
        long totalMessagesCount = chatMessageRepository.countByChatRoomId(roomId);
        long readMessagesCount = chatMessageReadStatusRepository.countByChatRoomIdAndReaderId(roomId, userId);
        return (int) (totalMessagesCount - readMessagesCount);
    }


    @Transactional
    public List<GroupChatMainResponse> getLatestGroupChats(Long lastChatRoomId) {
        List<ChatRoom> latestRooms;

        if (lastChatRoomId == null) {
            latestRooms = chatRoomRepository.findTop10ByGroupTrueOrderByCreatedAtDesc();
        } else {
            latestRooms = chatRoomRepository.findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(lastChatRoomId);
        }

        return latestRooms.stream()
                .map(this::toGroupChatMainResponse)
                .collect(Collectors.toList());
    }

    private GroupChatMainResponse toGroupChatMainResponse(ChatRoom chatRoom) {
        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoom.getId())
                .map(Image::getUrl)
                .orElse(null);
        String userCount = String.valueOf(chatRoom.getParticipants().size());
        return new GroupChatMainResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                roomImageUrl,
                userCount
        );
    }
    @Transactional
    public List<GroupChatMainResponse> getPopularGroupChats(int limit) {
        List<ChatRoom> popularRooms = chatRoomRepository.findTopByGroupTrueOrderByParticipantCountDesc(limit);
        return popularRooms.stream()
                .map(this::toGroupChatSearchResponse)
                .collect(Collectors.toList());
    }

    private GroupChatMainResponse toGroupChatSearchResponse(ChatRoom chatRoom) {
        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                        ImageType.CHAT_ROOM, chatRoom.getId())
                .map(Image::getUrl)
                .orElse(null);
        String userCount = String.valueOf(chatRoom.getParticipants().size());
        return new GroupChatMainResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                roomImageUrl,
                userCount
        );
    }
    @Transactional
    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅방에 참여하지 않은 사용자입니다."));
        List<ChatMessage> messages = chatMessageRepository.findTop50ByChatRoomIdOrderBySentAtDesc(roomId);

        return messages.stream()
                .map(message -> ChatMessageFirstResponse.fromEntity(message, chatRoom, imageRepository))
                .collect(Collectors.toList());
    }
    @Transactional
    public ChatRoom createGroupChatRoom(Long userId, GroupChatCreateRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatRoom groupChatRoom = new ChatRoom(
                true,
                Instant.now(),
                request.roomName(),
                request.description(),
                owner
        );
        ChatRoom savedChatRoom = chatRoomRepository.save(groupChatRoom);

        ChatParticipant participant = new ChatParticipant(savedChatRoom, owner);

        chatParticipantRepository.save(participant);

        if (request.roomImageUrl() != null && !request.roomImageUrl().isBlank()) {
            Image roomImage = Image.of(
                    ImageType.CHAT_ROOM,
                    savedChatRoom.getId(),
                    request.roomImageUrl(),
                    0
            );
            imageRepository.save(roomImage);
        }

        return savedChatRoom;
    }

}
