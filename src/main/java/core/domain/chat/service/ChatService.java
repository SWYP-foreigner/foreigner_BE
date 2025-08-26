package core.domain.chat.service;


import core.domain.chat.dto.ChatMessageResponse;
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
import core.global.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    public ChatService(ChatRoomRepository chatRoomRepo,
                       ChatParticipantRepository participantRepo, ChatMessageRepository chatMessageRepository,
                       UserRepository userRepository, ChatParticipantRepository chatParticipantRepository,
                       ChatMessageReadStatusRepository chatMessageReadStatusRepository, TranslationService translationService) {
        this.chatRoomRepo = chatRoomRepo;
        this.participantRepo = participantRepo;

        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.chatMessageReadStatusRepository = chatMessageReadStatusRepository;
        this.translationService = translationService;
    }
    public List<ChatRoom> getMyChatRooms(Long userId) {
        List<ChatParticipant> participants = chatParticipantRepository.findByUserIdExplicit(userId);

        return participants.stream()
                .map(ChatParticipant::getChatRoom)
                .toList();
    }
    /**
     * 새로운 채팅방을 생성합니다.
     * 케이스 1: 1:1 채팅방 중복 생성 방지
     * 케이스 2: 채팅 참여자 유효성 검사 (최소 인원, 존재하지 않는 유저)
     * 케이스 3: 그룹 채팅 생성
     */
    @Transactional
    public ChatRoom createRoom(Long creatorId, List<Long> participantIds) {
        Set<Long> allParticipantIds = new HashSet<>(participantIds);
        allParticipantIds.add(creatorId);

        validateParticipants(allParticipantIds);

        if (allParticipantIds.size() == 2) {
            Optional<ChatRoom> existingRoomOpt = find1on1Room(allParticipantIds);

            if (existingRoomOpt.isPresent()) {
                return existingRoomOpt.get();
            }
        }

        List<User> participants = userRepository.findAllById(allParticipantIds);

        ChatRoom newRoom = new ChatRoom(allParticipantIds.size() > 2, Instant.now());
        chatRoomRepo.save(newRoom);

        addParticipantsToRoom(newRoom, participants);

        return newRoom;
    }

    private void validateParticipants(Set<Long> allParticipantIds) {
        if (allParticipantIds.isEmpty()) {
            throw new BusinessException(ErrorCode.CHAT_PARTICIPANT_MINIMUM);
        }

        long existingUserCount = userRepository.countByIdIn(allParticipantIds);
        if (existingUserCount != allParticipantIds.size()) {
            throw new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND);
        }
    }

    public Optional<ChatRoom> find1on1Room(Set<Long> allParticipantIds) {
        return chatRoomRepo.findOneOnOneRoomByParticipantIds(
                new ArrayList<>(allParticipantIds)
        );
    }

    private void addParticipantsToRoom(ChatRoom room, List<User> participants) {
        for (User user : participants) {
            participantRepo.save(new ChatParticipant(room, user));
        }
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
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        long remainingParticipants = participantRepo.countByChatRoomId(roomId);
        if (remainingParticipants == 0) {
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


    @Transactional
    public void rejoinRoom(Long roomId, Long userId) {
        ChatParticipant participant = participantRepo.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_JOIN_FAILED);
        }

        participant.reJoin();
    }



    @Transactional
    public void addParticipants(Long roomId, List<Long> userIds) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<User> usersToAdd = userRepository.findAllById(userIds);

        if (usersToAdd.size() != userIds.size()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        List<ChatParticipant> existingParticipants = chatParticipantRepository.findByChatRoomIdAndUserIdIn(roomId, userIds);
        Set<Long> existingUserIds = existingParticipants.stream()
                .map(p -> p.getUser().getId())
                .collect(Collectors.toSet());

        List<User> newUsers = usersToAdd.stream()
                .filter(user -> !existingUserIds.contains(user.getId()))
                .toList();

        List<ChatParticipant> newParticipants = newUsers.stream()
                .map(user -> new ChatParticipant(room, user))
                .collect(Collectors.toList());

        chatParticipantRepository.saveAll(newParticipants);
    }
}
