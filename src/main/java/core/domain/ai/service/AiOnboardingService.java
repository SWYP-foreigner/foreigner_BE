package core.domain.ai.service;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
// import org.springframework.transaction.annotation.Transactional; // 성능을 위해 제거 (필요시 개별 메서드에 적용)

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random; // ✅ 랜덤 간격 계산용 추가

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final ImageService imageService;
    private final UserRoleDetectService userRoleDetectService;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;

    // 메시지 문구 랜덤 선택용 (보안 강도 높음)
    private static final SecureRandom secureRandom = new SecureRandom();

    // ✅ [설정 변경] 가입 후 24시간(1440분) 동안 동작
    private static final int ONBOARDING_WINDOW_MINUTES = 1440;

    /**
     * 주기적으로 실행되어 신규 유저에게 AI가 선톡을 보냄
     * (전체 트랜잭션 제거하여 DB 잠금 최소화)
     */
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();
        Instant timeLimit = now.minus(Duration.ofMinutes(ONBOARDING_WINDOW_MINUTES));

        // 가입한 지 24시간 이내인 유저 조회
        List<User> newUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, timeLimit);
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);

        if (allAiCharacters.isEmpty()) return;

        for (User user : newUsers) {
            try {
                processUserOnboarding(user, allAiCharacters, now);
            } catch (Exception e) {
                log.error("Failed onboarding for user: {}", user.getId(), e);
            }
        }
    }

    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        if (!this.isProfileComplete(user)) {
            return;
        }

        // 가입 후 흐른 시간 (분)
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();

        // ---------------------------------------------------------------
        // ✅ [핵심 로직 변경] 유저별 고유 랜덤 스케줄 계산 (5~15분 간격)
        // ---------------------------------------------------------------
        int expectedAiCount = 0;
        long accumulatedTime = 0;

        // "현재 시간까지 이 유저에게 몇 명의 AI가 말을 걸었어야 정상인가?"를 계산
        while (true) {
            // Seed를 고정하여, 서버가 재시작되거나 스케줄러가 다시 돌아도
            // '이 유저의 N번째 AI 도착 시간'은 항상 똑같이 계산됨 (Deterministic Random)
            long seed = user.getId() + (expectedAiCount * 997L);
            Random seededRandom = new Random(seed);

            // 5분 ~ 15분 사이 랜덤 값 추출 (5 + 0~10)
            long nextInterval = 5 + seededRandom.nextInt(11);

            accumulatedTime += nextInterval;

            // 누적 시간이 가입 후 흐른 시간을 넘어서면, 아직 보낼 때가 아님 -> 루프 종료
            if (accumulatedTime > minutesSinceJoined) {
                break;
            }

            // 보낼 시간이 지났으면 카운트 증가
            expectedAiCount++;
        }
        // ---------------------------------------------------------------

        // 현재 이 유저와 대화 중인 AI 수 조회
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());

        // 이미 충분히 받았다면 스킵
        if (currentAiCount >= expectedAiCount) return;

        // 보유한 AI 캐릭터 수보다 더 많이 보낼 순 없음
        if (currentAiCount >= allAiCharacters.size()) return;

        // 안 만난 AI 찾아서 매칭
        User selectedAi = findUnusedAi(user, allAiCharacters);

        if (selectedAi != null) {
            createRoomAndSendFirstMessage(user, selectedAi);
            log.info("✅ Onboarding Msg Sent: AI[{}] -> User[{}] (Joined: {}m, Target: {}th)",
                    selectedAi.getFirstName(), user.getFirstName(), minutesSinceJoined, expectedAiCount);
        }
    }

    private User findUnusedAi(User user, List<User> allAiCharacters) {
        List<Long> metAiIds = chatRoomRepository.findPartnerIdsByUserId(user.getId());

        List<User> availableAis = allAiCharacters.stream()
                .filter(ai -> !metAiIds.contains(ai.getId()))
                .toList();

        if (availableAis.isEmpty()) return null;

        return availableAis.get(secureRandom.nextInt(availableAis.size()));
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
        } catch (Exception e) {
            log.error("❌ Failed to send AI onboarding message via pipeline", e);
        }
    }

    private String generateWelcomeMessage() {
        String[] greetings = {
                // 기존 문구
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?",

                // 추가된 문구
                "Hey! How's it going?",
                "Hi! Hope you're having a good week.",
                "Hello! Just wanted to say hi.",
                "Hi, nice to connect with you!",
                "What are you up to right now?",
                "Did you have a good lunch?",
                "Hi! How was your day today?",
                "It's nice to meet you.",
                "What time is it over there?",
                "Hello from Seoul!",
                "Do you like watching K-dramas?",
                "What kind of music do you listen to?",
                "Do you like Korean food?",
                "Any movie recommendations?",
                "Do you like traveling?",
                "What do you usually do for fun?",
                "Do you play any video games?",
                "Do you like coffee or tea?",
                "What's your favorite song these days?",
                "Do you like animals?",
                "I like your photos!",
                "You seem like a fun person.",
                "I'm looking for a language exchange partner.",
                "I want to improve my English. Can you help?",
                "Have you ever visited Korea?",
                "Do you know any Korean words?",
                "I'm looking for global friends.",
                "Your style looks great!",
                "Do you use this app often?",
                "The weather is so nice today.",
                "Do you have any plans for the weekend?",
                "I'm bored too, want to chat?",
                "Are you a student or working?",
                "What's your MBTI?"
        };
        return greetings[secureRandom.nextInt(greetings.length)];
    }

    public boolean isProfileComplete(User user) {
        String userProfileKey = imageService.getUserProfileKey(user.getId());
        return user.getBirthdate() != null
                && user.getLastName() != null
                && user.getFirstName() != null
                && user.getPurpose() != null
                && user.getIntroduction() != null
                && user.getLanguage() != null
                && user.getHobby() != null
                && user.getSex() != null
                && userProfileKey != null;
    }
}