package core.domain.chat.service;


import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.dto.ChatRoomSummaryResponse;
import core.domain.chat.dto.MessageSentEvent;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSummaryService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ImageRepository imageRepository;
    private final ChatParticipantRepository chatParticipantRepository;

    /**
     * [핵심 로직] 비동기 스레드에서 호출되어 새로운 트랜잭션 내에서 Summary를 생성하고 이벤트를 발행합니다.
     * `@Transactional(REQUIRES_NEW)`를 통해 No Session / Lazy Loading 문제를 해결합니다.
     *
     * @param messageResponse 메인 스레드에서 생성한 메시지 DTO
     * @param recipientIds 수신자 ID 목록
     * @param absoluteStartTime 성능 측정 시작 시간
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendSummaryToRecipientsInNewTx(
            ChatMessageResponse messageResponse,
            List<Long> recipientIds,
            long absoluteStartTime
    ) {
        Long roomId = messageResponse.roomId();

        // recipientIds는 이미 차단된 유저가 걸러지고, 번역 언어별로 그룹핑된 유저 목록입니다.
        for (Long recipientId : recipientIds) {

            // 1. 수신자별 맞춤형 Summary 생성 (unreadCount 계산 포함)
            // 새로운 트랜잭션 안에서 안전하게 DB 접근
            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(roomId, recipientId);

            // 2. 이벤트 발행 (메시지 + 수신자 1인 + Summary)
            // recipientIds를 1개만 포함하는 List로 발행하여 정확히 1인에게만 전송되도록 함
            eventPublisher.publishEvent(
                    new MessageSentEvent(messageResponse, List.of(recipientId), summary, absoluteStartTime)
            );
        }
    }

    /**
     * Summary DTO를 생성합니다.
     * 이 메서드는 `sendSummaryToRecipientsInNewTx`의 트랜잭션 내에서 실행됩니다.
     *
     * @param roomId 채팅방 ID
     * @param forUserId 요약 정보를 받을 사용자 ID (이 사용자의 unreadCount를 계산)
     * @return ChatRoomSummaryResponse
     */
    @Transactional(readOnly = true)
    protected ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
        // Fetch Join 쿼리 사용을 권장하지만, 현재 ChatMessageService의 코드를 기반으로 findById를 사용합니다.
        // REQUIRES_NEW 트랜잭션 덕분에 findById(Lazy Loading 가능)도 안전해집니다.
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        // 1. 마지막 메시지
        ChatMessage lastMsg = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId)
                .orElse(null);

        String lastContent = (lastMsg != null) ? lastMsg.getContent() : "대화를 시작해보세요.";
        Instant lastTime = (lastMsg != null) ? lastMsg.getSentAt() : room.getCreatedAt();

        // 2. 안 읽은 메시지 수: 수신자(forUserId) 기준으로 계산
        int unread = countUnreadMessages(roomId, forUserId);

        String name = room.getRoomName();
        String img = null;

        // 3. 1:1 채팅방 처리 (상대방 이름, 이미지 URL)
        if (!room.getIsGroup()) {
            Optional<User> opponentOpt = room.getParticipants().stream()
                    .map(p -> p.getUser())
                    .filter(u -> !u.getId().equals(forUserId))
                    .findFirst();

            if (opponentOpt.isPresent()) {
                User opponent = opponentOpt.get();
                name = opponent.getFirstName() + " " + opponent.getLastName();

                // 이미지 조회 (DB 접근)
                img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                        .map(url -> url.getUrl())
                        .orElse(null);
            } else {
                name = "Unknown";
            }
        }
        // 4. 그룹 채팅방 처리 (그룹 이미지 URL)
        else {
            // 그룹 이미지 조회 (DB 접근)
            img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, roomId)
                    .map(url -> url.getUrl())
                    .orElse(null);
        }

        // 5. 최종 Summary Response
        return new ChatRoomSummaryResponse(
                room.getId(), name, lastContent, lastTime, img, unread, room.getParticipants().size()
        );
    }
    private int countUnreadMessages(Long roomId, Long userId) {
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId).orElse(0L);
        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
    }
}
