package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatReport;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatReportRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
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
    private final BlockRepository blockRepository;
    private final ChatReportRepository chatReportRepository;
    private final UserRoleDetectService userRoleDetectService;
    private final ImageService imageService;
    private final ChatMessageRepository chatMessageRepository;
    @Transactional(readOnly = true)
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {

        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> participants = chatParticipantRepository.findActiveParticipants(roomId);
        return participants.stream()
                .map(p -> {
                    String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                                    ImageType.USER, p.getUser().getId())
                            .map(Image::getUrl)
                            .orElse(null);

                    boolean isHost = chatRoom.getOwner() != null
                            && chatRoom.getOwner().getId().equals(p.getUser().getId());

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

    @Transactional(readOnly = true)
    public ChatUserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        String image = imageService.getUserProfileKey(userId);

        return ChatUserProfileResponse.from(user, image);
    }

    // --- 차단 및 신고 ---

    /**
     * 사용자 차단
     * @param targetUserId 차단할 대상 ID
     * @param blockerId 차단을 요청한 사용자(나) ID (Controller에서 Principal로 넘겨받음)
     */
    @Transactional
    public void blockChatUser(Long targetUserId, Long blockerId) { // 파라미터 수정됨
        if (targetUserId.equals(blockerId)) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }
        User blocker = userRepository.findById(blockerId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        User blockedUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        userRoleDetectService.isProfileSetUpUser(blocker);
        if (blockRepository.existsBlock(blocker.getId(), blockedUser.getId())) {
            throw new BusinessException(UserErrorCode.ALREADY_BLOCKED);
        }
        blockRepository.save(new BlockUser(blocker, blockedUser));
    }
    @Transactional
    public void reportChat(Long reporterUserId, ChatReportRequest request) {

        User reporterUser = null;

        // 1. [수정] 신고자가 사람(User)일 때만 유효성 검사 수행
        if (reporterUserId != null) {
            // 중복 신고 체크
            if (chatReportRepository.existsByReporterUserIdAndMessageId(reporterUserId, request.messageId())) {
                throw new BusinessException(ChatErrorCode.DUPLICATE_REPORT);
            }

            // 신고자 조회
            reporterUser = userRepository.findById(reporterUserId)
                    .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        }

        // 2. 신고 대상 메시지 조회
        ChatMessage reportedMessage = chatMessageRepository.findById(request.messageId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        User reportedUser = reportedMessage.getSender();
        ChatRoom chatRoom = reportedMessage.getChatRoom();

        // 3. [수정] 자진 신고 방지 (신고자가 있을 때만 체크)
        if (reporterUserId != null && reportedUser.getId().equals(reporterUserId)) {
            throw new BusinessException(ChatErrorCode.CANNOT_REPORT_SELF);
        }

        // 4. 리포트 생성 (reporterUser가 null이면 시스템 신고로 저장)
        ChatReport chatReport = new ChatReport(
                reporterUser, // 여기가 null이어도 들어가도록 허용
                reportedUser,
                chatRoom,
                request.messageId(),
                reportedMessage.getContent(),
                request.reasonCategory(),
                request.reasonDetail()
        );

        chatReportRepository.save(chatReport);
    }
    // --- 설정 (번역, 알림) ---

    @Transactional
    public void toggleTranslation(Long roomId, Long userId, boolean enable) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        participant.toggleTranslation(enable);
        chatParticipantRepository.save(participant);
    }

    @Transactional
    public void toggleChatRoomNotifications(Long roomId, Long userId, boolean enabled) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        participant.setNotificationsEnabled(enabled);
    }

    @Transactional(readOnly = true)
    public ChatNotificationStatusResponse isNotificationsEnabled(Long roomId, Long userId) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        return new ChatNotificationStatusResponse(participant.isNotificationsEnabled());
    }
}