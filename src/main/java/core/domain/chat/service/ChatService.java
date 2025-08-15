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
    public boolean deleteMessage(String messageId) {
        if (!messageRepo.existsById(messageId)) {
            return false;
        }
        messageRepo.deleteById(messageId);
        return true;
    }

}
