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
import core.global.enums.common.ImageType;
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
import java.util.Map;
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
     *
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendSummaryToRecipientsInNewTx(
            ChatMessageResponse messageResponse,
            List<Long> recipientIds,
            Map<String, String> translations // 서비스에서 넘어온 번역본 맵 (없으면 Collections.emptyMap())
    ) {
        Long roomId = messageResponse.roomId();

        for (Long recipientId : recipientIds) {
            // 1. 각 유저별로 커스텀된 요약본(unreadCount 등 포함) 생성
            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(roomId, recipientId);

            // 2. 바뀐 MessageSentEvent 생성자에 맞춰 파라미터 구성
            // 이 메서드는 요약본 업데이트용이므로, 언어별 맵에는 자기 자신만 넣어서 보냅니다.
            Map<String, List<Long>> singleRecipientMap = Map.of("NONE", List.of(recipientId));

            eventPublisher.publishEvent(
                    new MessageSentEvent(
                            messageResponse,      // 기본 메시지 정보
                            singleRecipientMap,   // 웹소켓 전송 대상 (방 목록 갱신용)
                            List.of(),            // 푸시 알림 대상 (이미 메인 로직에서 보냈다면 빈 리스트)
                            translations,         // 번역 결과 맵
                            summary               // 유저별 요약 정보
                    )
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
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));


        ChatMessage lastMsg = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId)
                .orElse(null);

        String lastContent = (lastMsg != null) ? lastMsg.getContent() : "대화를 시작해보세요.";
        Instant lastTime = (lastMsg != null) ? lastMsg.getSentAt() : room.getCreatedAt();

        int unread = countUnreadMessages(roomId, forUserId);

        String name = room.getRoomName();
        String img = null;

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
        else {
            img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, roomId)
                    .map(url -> url.getUrl())
                    .orElse(null);
        }
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
