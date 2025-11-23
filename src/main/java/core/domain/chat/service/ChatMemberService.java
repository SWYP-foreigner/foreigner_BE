package core.domain.chat.service;


import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatReportRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMemberService {

    private final ChatParticipantRepository chatParticipantRepository;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ImageRepository imageRepository;
    private final BlockRepository blockRepository; // 차단 관련
    private final ChatReportRepository chatReportRepository; // 신고 관련
    private final UserRoleDetectService userRoleDetectService;

    @Transactional(readOnly = true)
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        return chatParticipantRepository.findByChatRoom(chatRoom).stream()
                .map(p -> {
                    String img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, p.getUser().getId())
                            .map(Image::getUrl).orElse(null);
                    boolean isHost = chatRoom.getOwner() != null && chatRoom.getOwner().getId().equals(p.getUser().getId());
                    return new ChatRoomParticipantsResponse(p.getUser().getId(), p.getUser().getFirstName(), p.getUser().getLastName(), img, isHost);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChatUserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        String img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .map(Image::getUrl).orElse(null);

        return new ChatUserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(), img, user.getCountry());
    }

    // --- 차단 및 신고 ---
    @Transactional
    public void blockChatUser(Long targetUserId) {
        // 차단 로직 (BlockRepository 사용)
    }

    @Transactional
    public void reportChat(Long reporterId, ChatReportRequest req) {
        // 신고 로직 (ChatReportRepository 사용)
    }

    // --- 설정 (번역, 알림) ---
    @Transactional
    public void toggleTranslation(Long roomId, Long userId, boolean enabled) {
        ChatParticipant p = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        p.setTranslateEnabled(enabled);
    }

    @Transactional
    public void toggleChatRoomNotifications(Long roomId, Long userId, boolean enabled) {
        ChatParticipant p = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        p.setNotificationEnabled(enabled);
    }

    @Transactional(readOnly = true)
    public ChatNotificationStatusResponse isNotificationsEnabled(Long roomId, Long userId) {
        ChatParticipant p = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        return new ChatNotificationStatusResponse(p.isNotificationEnabled());
    }
}