package core.domain.user.service;

import core.domain.user.dto.CommendUsersProfileResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.FollowStatus;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.UserErrorCode;
import core.global.entity.image.repository.ImageRepository;
import lombok.AllArgsConstructor; // [추가]
import lombok.Getter; // [추가]
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant; // [추가]
import java.time.temporal.ChronoUnit; // [추가]
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentBasedRecommender {

    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;

    private static final double KOREAN_PRIORITY_BOOST = 0.5;
    private static final double TEMPERATURE = 0.7;
    private static final double ACTIVITY_SCORE_HALF_LIFE_DAYS = 1.0;
    private static final java.security.SecureRandom RAND = new java.security.SecureRandom();
    private final ImageService imageService;



    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {

        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<FollowStatus> statusesToExclude = List.of(FollowStatus.PENDING, FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);
        if (excludeIds.isEmpty()) {
            excludeIds.add(0L);
        }

        List<User> allCandidates = userRepository.findFullProfiledRecommendationCandidates(excludeIds);

        if (allCandidates.isEmpty()) {
            return List.of();
        }

        boolean meIsKorean = isKorean(me.getCountry());

        List<Scored<User>> scoredCandidates;

        if (meIsKorean) {
            scoredCandidates = allCandidates.stream()
                    .filter(user -> !isKorean(user.getCountry()))
                    .map(user -> new Scored<>(
                            user,
                            calculateActivityScore(user, ACTIVITY_SCORE_HALF_LIFE_DAYS)
                    ))
                    .collect(Collectors.toList());

        } else {
            scoredCandidates = allCandidates.stream()
                    .map(user -> {
                        double activityScore = calculateActivityScore(user, ACTIVITY_SCORE_HALF_LIFE_DAYS);
                        double boost = isKorean(user.getCountry()) ? KOREAN_PRIORITY_BOOST : 0.0;
                        return new Scored<>(user, activityScore + boost);
                    })
                    .collect(Collectors.toList());
        }

        if (scoredCandidates.isEmpty()) {
            return List.of();
        }

        List<User> chosen = pickGumbelTopK(scoredCandidates, limit, TEMPERATURE);
        return chosen.stream().map(this::toDto).toList();
    }

    /**
     * [신규 헬퍼 메서드]
     * 국가 문자열을 기반으로 한국인 여부를 판단합니다.
     * TODO: DB에 저장된 실제 '한국' 값으로 변경하세요 (예: "KR", "Republic of Korea" 등)
     */
    private boolean isKorean(String country) {
        if (country == null || country.isBlank()) {
            return false;
        }
        String c = country.trim();
        return "South Korea".equalsIgnoreCase(c) || "Korea".equalsIgnoreCase(c);
    }


    @Getter @AllArgsConstructor
    private static class Scored<T> { private T item; private double score; }

    /** Gumbel-Top-k: key = score/T + Gumbel(0,1) 로 정렬 → 상위 limit 선택(중복 없음) */
    private List<User> pickGumbelTopK(List<Scored<User>> pool, int limit, double temperature) {
        class Draw { final User u; final double key; Draw(User u, double key){this.u=u; this.key=key;} }
        List<Draw> draws = new ArrayList<>(pool.size());
        double T = Math.max(1e-6, temperature);
        for (Scored<User> s : pool) {
            double u = RAND.nextDouble();
            double gumbel = -Math.log(-Math.log(u));
            double key = (s.score / T) + gumbel;
            draws.add(new Draw(s.item, key));
        }
        draws.sort((a, b) -> Double.compare(b.key, a.key));
        return draws.stream().limit(Math.max(1, limit)).map(d -> d.u).toList();
    }

    /** 사용자의 마지막 활동 시간(lastSeenAt)을 바탕으로 0.0 ~ 1.0 사이의 활동 점수를 계산 */
    private double calculateActivityScore(User user, double halfLifeDays) {
        if (user.getLastSeenAt() == null) {
            return 0.0;
        }
        long hoursSinceUpdate = ChronoUnit.HOURS.between(user.getLastSeenAt(), Instant.now());
        double daysSinceUpdate = hoursSinceUpdate / 24.0;
        double decayRate = Math.log(2) / halfLifeDays;
        return Math.exp(-decayRate * daysSinceUpdate);
    }

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private CommendUsersProfileResponse toDto(User u) {
        String imageKey = imageService.getUserProfileKey(u.getId());

        return new CommendUsersProfileResponse(u,  csvToSet(u.getLanguage()).stream().toList(), csvToSet(u.getHobby()).stream().toList(),imageKey);
    }
}