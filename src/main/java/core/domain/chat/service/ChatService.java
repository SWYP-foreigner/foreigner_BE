package core.domain.chat.service;


import core.domain.chat.dto.ChatMessageDoc;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ChatService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final ChatMessageRepository chatMessageRepository; // 추가
    private final UserRepository userRepository; // User 엔티티가 필요하므로 추가

    public ChatService(ChatRoomRepository chatRoomRepo,
                       ChatParticipantRepository participantRepo,
                       ChatMessageRepository messageRepo,
                       UserRepository userRepo, ChatMessageRepository chatMessageRepository, UserRepository userRepository) {
        this.chatRoomRepo = chatRoomRepo;
        this.participantRepo = participantRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
    }

    public List<ChatRoom> getAllRooms() {
        return chatRoomRepo.findAll();
    }
    @Transactional
    public ChatRoom createRoom(boolean isGroup, List<Long> participantIds) {
        ChatRoom room = new ChatRoom(isGroup, Instant.now());
        room = chatRoomRepo.save(room);

        if (participantIds != null && !participantIds.isEmpty()) {
            List<User> users = userRepo.findAllById(participantIds);

            if (users.size() != participantIds.size()) {
                throw new IllegalArgumentException("참여자 중 존재하지 않는 사용자가 있습니다.");
            }

            for (User user : users) {
                ChatParticipant participant = new ChatParticipant(room, user);
                participantRepo.save(participant);
            }
        }

        return room;
    }
    @Transactional
    public void inviteParticipants(Long roomId, List<Long> userIds) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        List<User> users = userRepo.findAllById(userIds);
        if (users.size() != userIds.size()) {
            throw new IllegalArgumentException("참여자 중 존재하지 않는 사용자가 있습니다.");
        }

        for (User user : users) {
            boolean exists = participantRepo.existsByChatRoomIdAndUserId(roomId, user.getId());
            if (!exists) {
                ChatParticipant participant = new ChatParticipant(room, user);
                participantRepo.save(participant);
            }
        }

        if (participantRepo.countByChatRoomId(roomId) > 2) {
            room.changeToGroupChat();
        }
    }


    @Transactional
    public boolean deleteRoom(Long roomId) {
        if (!chatRoomRepo.existsById(roomId)) {
            return false;
        }
        chatRoomRepo.deleteById(roomId);
        return true;
    }


    public List<ChatParticipant> getParticipants(Long roomId) {
        return participantRepo.findByChatRoomId(roomId);
    }

    public List<ChatMessage> getMessages(Long roomId) {
        // PostgreSQL에서 메시지를 조회합니다.
        return chatMessageRepository.findByChatRoomIdOrderBySentAtAsc(roomId);
    }
    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        // ChatRoom 및 User 엔티티를 찾습니다.
        ChatRoom chatRoom = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        ChatMessage chatMessage = new ChatMessage(chatRoom, sender, content);

        return chatMessageRepository.save(chatMessage);
    }

    @Transactional
    public boolean deleteMessage(Long messageId) {
        if (!chatMessageRepository.existsById(messageId)) {
            return false;
        }
        chatMessageRepository.deleteById(messageId);
        return true;
    }
    @Transactional
    public void markMessagesAsRead(Long roomId, Long readerId) {
        ChatRoom chatRoom = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        int memberCount = chatRoom.getParticipants().size();

        List<ChatMessage> unreadMessages = chatMessageRepository.findUnreadMessages(roomId, memberCount);

        for (ChatMessage msg : unreadMessages) {
            // TODO: 누가 읽었는지에 대한 로직 추가 필요. 현재는 단순히 읽은 사람 수만 증가
            msg.setReadCount(msg.getReadCount() + 1);
        }
    }
    @Transactional(readOnly = true)
    public List<ChatMessage> searchMessages(Long roomId, String keyword) {
        return chatMessageRepository.findByChatRoomIdAndContentContaining(roomId, keyword);
    }

}
