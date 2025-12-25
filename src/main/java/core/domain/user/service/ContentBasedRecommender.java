package core.domain.user.service;

import core.domain.user.dto.CommendUsersProfileResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.FollowStatus;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentBasedRecommender {

    private final UserRepository userRepository;
    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final ImageService imageService;
    private final SecureRandom secureRandom = new SecureRandom();
    private static final double WEIGHT_ACTIVITY = 85.0;
    private static final double WEIGHT_SIMILARITY = 10.0;
    // 랜덤 비중을 5로 축소 (활동적인 사람이 랜덤 운 때문에 밀려나지 않도록)
    private static final double WEIGHT_RANDOM = 5.0;
    // [핵심] 반감기를 '1일'에서 '0.25일(6시간)' 또는 '0.1일(2.4시간)'으로 단축
    private static final double ACTIVITY_HALF_LIFE_DAYS = 0.1;


    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {
        // 1. 내 정보 조회
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 제외 대상 필터링 (팔로잉, 차단, 본인)
        List<FollowStatus> statusesToExclude = List.of(FollowStatus.PENDING, FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);
        if (excludeIds.isEmpty()) excludeIds.add(0L);

        Instant activeLimit = Instant.now().minus(3, ChronoUnit.DAYS);

        List<User> candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);
        if (candidates.isEmpty()) {
            activeLimit = Instant.now().minus(14, ChronoUnit.DAYS);
            candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 4. 내 언어/취미 Set으로 변환 (비교 편의성)
        String myCountry = me.getCountry();
        Set<String> myHobbies = csvToSet(me.getHobby());

        // 5. 점수 계산 및 정렬, 상위 3명 추출
        List<UserScore> scoredCandidates = candidates.stream()
                .map(candidate -> {
                    double score = calculateTotalScore(candidate, myCountry, myHobbies);
                    return new UserScore(candidate, score);
                })
                .sorted(Comparator.comparingDouble(UserScore::getScore).reversed()) // 1. 점수 내림차순 정렬
                .collect(Collectors.toList());

        // [핵심 변경] 상위 N명을 가져와서 섞음 (Shuffle)
        // 이유: 활동성 점수가 워낙 강력해서 상위권이 고정되므로, 상위 20명 내에서 랜덤성을 부여
        int poolSize = Math.min(scoredCandidates.size(), 20); // 상위 20명 (후보가 적으면 전체)

        List<UserScore> topTierPool = new ArrayList<>(scoredCandidates.subList(0, poolSize));
        Collections.shuffle(topTierPool); // 여기가 마법의 한 줄 (순서를 뒤섞음)

        // 섞인 목록에서 3명 뽑기
        return topTierPool.stream()
                .limit(limit)
                .map(us -> toDto(us.getUser()))
                .collect(Collectors.toList());
    }

    /**
     * [총점 계산 로직]
     * Total = (활동성 * 85) + (유사도 * 10) + (랜덤 * 5)
     */
    private double calculateTotalScore(User candidate, String myCountry, Set<String> myHobbies) {
        double activityScore = calculateActivityScore(candidate);
        // [변경] 파라미터 변경
        double similarityScore = calculateSimilarityScore(candidate, myCountry, myHobbies);
        double randomNoise = secureRandom.nextDouble();

        return (activityScore * WEIGHT_ACTIVITY)
                + (similarityScore * WEIGHT_SIMILARITY)
                + (randomNoise * WEIGHT_RANDOM);
    }
    /**
     * [활동성 점수] 0.0 ~ 1.0
     * 최근 접속일수록 1.0에 가까움. 시간이 지날수록 지수적으로 감소.
     */
    private double calculateActivityScore(User user) {
        if (user.getLastSeenAt() == null) return 0.0;

        long hoursSinceLastSeen = ChronoUnit.HOURS.between(user.getLastSeenAt(), Instant.now());
        // 미래 시간인 경우 방어 로직
        if (hoursSinceLastSeen < 0) hoursSinceLastSeen = 0;

        double daysSince = hoursSinceLastSeen / 24.0;
        double decayRate = Math.log(2) / ACTIVITY_HALF_LIFE_DAYS;

        return Math.exp(-decayRate * daysSince);
    }

    /**
     * [유사도 점수] 0.0 ~ 1.0
     * 국적(2.0) + 취미(1.0) 가중치 적용
     */
    private double calculateSimilarityScore(User candidate, String myCountry, Set<String> myHobbies) {
        // 1. 국적 점수 계산 (가중치 2.0)
        // 국적이 같으면 2점, 다르면 0점
        double countryScore = 0.0;
        if (myCountry != null && myCountry.equalsIgnoreCase(candidate.getCountry())) {
            countryScore = 2.0;
        }

        // 2. 취미 점수 계산 (가중치 1.0)
        // 일치하는 취미 개수 * 1.0
        Set<String> candidateHobbies = csvToSet(candidate.getHobby());
        long hobbyMatchCount = candidateHobbies.stream()
                .filter(myHobbies::contains).count();
        double hobbyScore = hobbyMatchCount * 1.0;

        // 3. 정규화 (0.0 ~ 1.0 사이 값으로 변환)
        // 분모 = 국적 만점(2.0) + 내 취미 개수(다 맞았을 때)
        double maxPossibleScore = 2.0 + Math.max(1, myHobbies.size());

        double totalScore = countryScore + hobbyScore;

        return Math.min(1.0, totalScore / maxPossibleScore);
    }
    // --- Helper Methods ---

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private CommendUsersProfileResponse toDto(User u) {
        String imageKey = imageService.getUserProfileKey(u.getId());
        return new CommendUsersProfileResponse(
                u,
                csvToSet(u.getLanguage()).stream().toList(),
                csvToSet(u.getHobby()).stream().toList(),
                imageKey
        );
    }

    // 내부 점수 래퍼 클래스
    @lombok.AllArgsConstructor
    @lombok.Getter
    private static class UserScore {
        private User user;
        private double score;
    }
}