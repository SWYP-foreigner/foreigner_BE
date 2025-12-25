package core.domain.ai.service;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;

    // 신규 유저 관리 기간 (가입 후 2시간 동안만 10분마다 말 걸기)
    private static final int ONBOARDING_WINDOW_MINUTES = 120;
    // 메시지 발송 간격 (10분)
    private static final int MESSAGE_INTERVAL_MINUTES = 10;

    @Transactional
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();
        Instant timeLimit = now.minus(Duration.ofMinutes(ONBOARDING_WINDOW_MINUTES));
        List<User> newUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, timeLimit);
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);
        if (allAiCharacters.isEmpty()) return;

        for (User user : newUsers) {
            processUserOnboarding(user, allAiCharacters, now);
        }
    }

    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();
        if (minutesSinceJoined < MESSAGE_INTERVAL_MINUTES) return;
        long expectedAiCount = minutesSinceJoined / MESSAGE_INTERVAL_MINUTES;
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());
        if (currentAiCount >= expectedAiCount) return;
        if (currentAiCount >= allAiCharacters.size()) return;
        User selectedAi = findUnusedAi(user, allAiCharacters);

        if (selectedAi != null) {
            createRoomAndSendFirstMessage(user, selectedAi);
            log.info("✅ Sent onboarding message: AI[{}] -> User[{}]", selectedAi.getFirstName(), user.getFirstName());
        }
    }

    private User findUnusedAi(User user, List<User> allAiCharacters) {
        List<Long> metAiIds = chatRoomRepository.findPartnerIdsByUserId(user.getId());
        List<User> availableAis = allAiCharacters.stream()
                .filter(ai -> !metAiIds.contains(ai.getId()))
                .toList();

        if (availableAis.isEmpty()) return null;
        return availableAis.get(ThreadLocalRandom.current().nextInt(availableAis.size()));
    }

    private void createRoomAndSendFirstMessage(User user, User ai) {
        ChatRoom chatRoom = chatRoomService.createRoom(user.getId(), ai.getId());
        String firstMessageContent = generateWelcomeMessage();
        SendMessageRequest request = new SendMessageRequest(
                chatRoom.getId(),
                ai.getId(),
                firstMessageContent,
                MessageType.TEXT
        );
        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("✅ AI Onboarding Message Sent via Pipeline: Room[{}] AI[{}] -> User[{}]",
                    chatRoom.getId(), ai.getFirstName(), user.getFirstName());
        } catch (Exception e) {
            log.error("❌ Failed to send AI onboarding message", e);
        }
    }

    // AI의 페르소나에 맞는 첫마디 생성
    private String generateWelcomeMessage() {
        String[] greetings = {
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?"
        };
        return greetings[ThreadLocalRandom.current().nextInt(greetings.length)];
    }
}