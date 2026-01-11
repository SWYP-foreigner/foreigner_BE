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
import java.time.Duration;
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

    // --- [가중치 설정] 접속 시간과 활동량에 '올인' ---
    private static final double WEIGHT_RECENCY = 0.6;    // 최근 접속 (60%) - 가장 중요
    private static final double WEIGHT_ACTIVITY = 0.3;   // 활동량 (30%) - 채팅/방문 많은 사람
    private static final double WEIGHT_SIMILARITY = 0.1; // 취향/국적 (10%) - 최소한의 필터

    // 활동 포인트 만점 기준 (예: 1000점이면 활동 점수 만점)
    private static final double MAX_ACTIVITY_POINT = 1000.0;
    private static final double MAX_VISIT_COUNT = 50.0;

    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {
        // 1. 내 정보 조회
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 제외 대상 필터링
        List<FollowStatus> statusesToExclude = List.of(FollowStatus.PENDING, FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);
        if (excludeIds.isEmpty()) excludeIds.add(0L);

        // [변경] 검색 범위: 최근 24시간 이내 접속자로 제한 (최우선)
        Instant activeLimit = Instant.now().minus(1, ChronoUnit.DAYS);
        List<User> candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);

        // [Fallback] 24시간 이내 접속자가 너무 적으면 최근 3일로 확장
        if (candidates.size() < 5) {
            activeLimit = Instant.now().minus(3, ChronoUnit.DAYS);
            candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 3. 내 취향 정보 준비
        String myCountry = me.getCountry();
        Set<String> myHobbies = csvToSet(me.getHobby());

        // 4. 점수 계산 (Recency + Activity 집중)
        List<UserScore> scoredCandidates = candidates.stream()
                .map(candidate -> {
                    double score = calculateScore(candidate, myCountry, myHobbies);
                    return new UserScore(candidate, score);
                })
                .sorted(Comparator.comparingDouble(UserScore::getScore).reversed())
                .collect(Collectors.toList());

        // 5. 상위권 셔플 (고인물 고착화 방지용 최소한의 셔플)
        // 점수가 높은 상위 10명 중에서만 랜덤으로 3명을 뽑음
        int poolSize = Math.min(scoredCandidates.size(), 10);
        List<UserScore> topTierPool = new ArrayList<>(scoredCandidates.subList(0, poolSize));
        Collections.shuffle(topTierPool, secureRandom);

        return topTierPool.stream()
                .limit(limit)
                .map(us -> toDto(us.getUser()))
                .collect(Collectors.toList());
    }

    /**
     * [점수 계산 로직]
     * Score = (최근접속 * 0.6) + (활동량 * 0.3) + (유사도 * 0.1)
     */
    private double calculateScore(User candidate, String myCountry, Set<String> myHobbies) {

        // 1. Recency Score (최근 접속)
        double recencyScore = calculateRecencyScore(candidate.getLastSeenAt());

        // 2. Activity Score (활동량 - 포인트 + 방문수)
        double activityScore = calculateActivityScore(candidate);

        // 3. Similarity Score (유사도)
        double similarityScore = calculateSimilarityScore(candidate, myCountry, myHobbies);

        // 최종 합산
        return (recencyScore * WEIGHT_RECENCY)
                + (activityScore * WEIGHT_ACTIVITY)
                + (similarityScore * WEIGHT_SIMILARITY);
    }

    /**
     * [최근 접속 점수]
     * 10분 이내 접속 시 1.0 (초강력), 시간이 지날수록 급격히 하락
     */
    private double calculateRecencyScore(Instant lastSeenAt) {
        if (lastSeenAt == null) return 0.0;

        long minutesAgo = Duration.between(lastSeenAt, Instant.now()).toMinutes();
        if (minutesAgo <= 10) return 1.0; // 방금 접속한 사람 최고 우대
        if (minutesAgo <= 60) return 0.9; // 1시간 이내

        // 24시간(1440분)이 지나면 점수가 0에 가깝게 떨어짐
        return Math.exp(-((double) minutesAgo / 1440.0));
    }

    /**
     * [활동량 점수]
     * 채팅 포인트(ActivityPoint)와 방문 횟수(VisitCount)를 반영
     */
    private double calculateActivityScore(User u) {
        long points = (u.getActivityPoint() != null) ? u.getActivityPoint() : 0L;
        long visits = (u.getVisitCount() != null) ? u.getVisitCount() : 0L;

        // 포인트 점수 (최대 1.0)
        double pScore = Math.min(points / MAX_ACTIVITY_POINT, 1.0);

        // 방문 점수 (최대 1.0)
        double vScore = Math.min(visits / MAX_VISIT_COUNT, 1.0);

        // 활동 포인트(채팅)에 더 가중치 (7:3)
        return (pScore * 0.7) + (vScore * 0.3);
    }

    /**
     * [유사도 점수] - 보조 지표
     */
    private double calculateSimilarityScore(User candidate, String myCountry, Set<String> myHobbies) {
        double score = 0.0;
        // 국적 같으면 0.5
        if (myCountry != null && myCountry.equalsIgnoreCase(candidate.getCountry())) {
            score += 0.5;
        }
        // 취미 겹치면 0.5 (개수 무관, 하나라도 겹치면)
        Set<String> candidateHobbies = csvToSet(candidate.getHobby());
        boolean hasCommonHobby = candidateHobbies.stream().anyMatch(myHobbies::contains);
        if (hasCommonHobby) {
            score += 0.5;
        }
        return score;
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

    @lombok.AllArgsConstructor
    @lombok.Getter
    private static class UserScore {
        private User user;
        private double score;
    }
}