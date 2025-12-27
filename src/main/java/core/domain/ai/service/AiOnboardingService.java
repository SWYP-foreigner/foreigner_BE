package core.domain.ai.service;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final ImageService imageService;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;

    // 메시지 문구 랜덤 선택용
    private static final SecureRandom secureRandom = new SecureRandom();

    // 1. 초기 온보딩 기간: 가입 후 48시간 (2880분)
    private static final int INITIAL_ONBOARDING_MINUTES = 48 * 60; // 2880분

    // 2. 확장 참여 유도 기간: 가입 후 3주 (21일)
    private static final int EXTENDED_WINDOW_DAYS = 21;

    // 3. 활동 유저 기준: 최근 1주일 (7일) 내 접속
    private static final int ACTIVE_USER_THRESHOLD_DAYS = 7;

    // 4. 확장 기간 중 메시지 발송 간격: 2시간 (120분)
    private static final int EXTENDED_INTERVAL_MINUTES = 120;

    /**
     * 주기적으로 실행되어 조건에 맞는 유저에게 AI가 선톡을 보냄
     */
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();

        // 검색 범위: 가입한 지 3주(21일) 이내인 유저 전체 조회
        Instant joinTimeLimit = now.minus(Duration.ofDays(EXTENDED_WINDOW_DAYS));

        // 쿼리 최적화: Role이 USER이고, createdAt이 3주 이내인 사람만 fetch
        List<User> targetUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, joinTimeLimit);
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);

        if (allAiCharacters.isEmpty()) return;

        for (User user : targetUsers) {
            try {
                processUserOnboarding(user, allAiCharacters, now);
            } catch (Exception e) {
                log.error("Failed onboarding process for user: {}", user.getId(), e);
            }
        }
    }

    // [Refactored] 메인 메서드: 흐름이 한눈에 보이도록 단순화 (복잡도 대폭 감소)
    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();

        // 1. 유효성 검사 (프로필, 최근 활동)
        if (!isValidUserForOnboarding(user, minutesSinceJoined, now)) {
            return;
        }

        // 2. 시뮬레이션: 지금까지 몇 명의 AI가 말을 걸었어야 하는지 계산
        int expectedAiCount = calculateExpectedAiCount(user, minutesSinceJoined);
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());

        // 3. 메시지 전송 시도 (조건 충족 시)
        if (currentAiCount < expectedAiCount) {
            trySendNewAiMessage(user, allAiCharacters, currentAiCount, minutesSinceJoined);
        }
    }

    // [Sub-Method 1] 유저가 메시지를 받을 자격이 있는지 검사
    private boolean isValidUserForOnboarding(User user, long minutesSinceJoined, Instant now) {
        if (!this.isProfileComplete(user)) {
            return false;
        }

        // 가입 48시간 이후인 경우, 최근 활동(1주일 이내)이 있어야 함
        if (minutesSinceJoined > INITIAL_ONBOARDING_MINUTES) {
            Instant activeThreshold = now.minus(Duration.ofDays(ACTIVE_USER_THRESHOLD_DAYS));
            return user.getLastSeenAt() != null && !user.getLastSeenAt().isBefore(activeThreshold);
        }

        return true;
    }

    // [Sub-Method 2] 복잡했던 while 루프 계산 로직 분리
    private int calculateExpectedAiCount(User user, long minutesSinceJoined) {
        int expectedAiCount = 0;
        long simulatedTime = 0;

        while (true) {
            long interval;

            if (simulatedTime < INITIAL_ONBOARDING_MINUTES) {
                // [Phase 1] 초기 48시간
                long seed = user.getId() + (expectedAiCount * 997L);
                Random seededRandom = new Random(seed);
                interval = 5L + seededRandom.nextInt(11);
            } else {
                // [Phase 2] 48시간 이후
                interval = EXTENDED_INTERVAL_MINUTES;
            }

            simulatedTime += interval;

            if (simulatedTime > minutesSinceJoined) {
                break;
            }
            expectedAiCount++;
        }
        return expectedAiCount;
    }

    // [Sub-Method 3] 스팸 검사 및 실제 메시지 전송 로직 분리
    private void trySendNewAiMessage(User user, List<User> allAiCharacters, long currentAiCount, long minutesSinceJoined) {
        // 🚨 스팸 방지: 답장 안 한 방이 5개 이상이면 중단
        long unrepliedRoomCount = chatRoomRepository.countUnrepliedAiRooms(user.getId());
        if (unrepliedRoomCount >= 5) {
            log.info("🚫 User[{}] ignores too many AIs ({}). Skip onboarding.", user.getId(), unrepliedRoomCount);
            return;
        }

        // 모든 AI 소진 체크
        if (currentAiCount >= allAiCharacters.size()) {
            return;
        }

        // 안 만난 AI 찾기 및 전송
        User selectedAi = findUnusedAi(user, allAiCharacters);
        if (selectedAi != null) {
            createRoomAndSendFirstMessage(user, selectedAi);
            log.info("✅ Auto Msg Sent: AI[{}] -> User[{}] (Joined: {}m, Phase: {})",
                    selectedAi.getFirstName(), user.getFirstName(),
                    minutesSinceJoined,
                    (minutesSinceJoined <= INITIAL_ONBOARDING_MINUTES) ? "Initial(48h)" : "Extended(3w)");
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
            log.error("❌ Failed to send AI onboarding message", e);
        }
    }

    private String generateWelcomeMessage() {
        String[] greetings = {
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?",
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