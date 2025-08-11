package core.domain.chat.service;


import core.domain.chat.dto.ChatMessageDoc;
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
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatMessageRepository messageRepo;
    private final UserRepository userRepo;

    public ChatService(ChatRoomRepository chatRoomRepo,
                       ChatParticipantRepository participantRepo,
                       ChatMessageRepository messageRepo,
                       UserRepository userRepo) {
        this.chatRoomRepo = chatRoomRepo;
        this.participantRepo = participantRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
    }

    public List<ChatRoom> getAllRooms() {
        return chatRoomRepo.findAll();
    }
    @Transactional
    public ChatRoom createRoom(boolean isGroup) {
        ChatRoom room = new ChatRoom(isGroup, Instant.now());
        return chatRoomRepo.save(room);
    }

    @Transactional
    public void deleteRoom(Long roomId) {
        chatRoomRepo.deleteById(roomId);
    }

    public List<ChatParticipant> getParticipants(Long roomId) {
        return participantRepo.findByChatRoomId(roomId);
    }

    public List<ChatMessageDoc> getMessages(Long roomId) {
        return messageRepo.findByRoomIdOrderByCreatedAtAsc(roomId);
    }

    @Transactional
    public ChatMessageDoc saveMessage(Long roomId, Long senderId, String content) {
        User user = userRepo.findById(senderId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid senderId"));
        ChatMessageDoc msg = new ChatMessageDoc(roomId, senderId, user.getName(), content);
        return messageRepo.save(msg);
    }

    @Transactional
    public void deleteMessage(String messageId) {
        messageRepo.deleteById(messageId);
    }
}
