package core.domain.user.service;

import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.repository.ImageRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentBasedRecommender {

    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private static final double W_PURPOSE = 0.4;
    private static final double W_COUNTRY = 0.2;
    private static final double W_AGE = 0.3;   // 너가 바꾼 가중치 유지
    private static final double W_LANG = 0.1;  // 너가 바꾼 가중치 유지
    private final BlockRepository blockRepository;
    /**
     랜덤 랭킹용 파라미터 (원하면 @Value 로 빼서 설정 가능)
     * POOL_MULTIPLIER = 5;
     * (limit * 5) 풀에서 추출
     * MIN_POOL:최소 풀 사이즈
     * TEMPERATURE: 클수록 랜덤성 증가
     */
    private static final int   POOL_MULTIPLIER = 2;
    private static final int   MIN_POOL       = 2;
    private static final double TEMPERATURE   = 0.1;

    private static final java.security.SecureRandom RAND = new java.security.SecureRandom();

    @Transactional(readOnly = true)
    public List<UserUpdateDTO> recommendForUser(Long meId, int limit) {
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        int meAge = safeAge(me.getBirthdate());
        Set<String> meLangs = csvToSet(me.getLanguage());

        Page<User> page = userRepository.findPageNullMemberAndMember(
                meId, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "updatedAt"))
        );

        log.info(">>>> [디버깅 1] DB에서 조회된 초기 후보 수: {}", page.getContent().size());

        if (!page.hasContent()) {
            return List.of();
        }
        List<User> filteredUsers = page.getContent().stream()
                .filter(candidate -> !isBlocked(me, candidate))
                .toList();

        log.info(">>>> [디버깅 1.5] 차단 필터링 후 남은 후보 수: {}", filteredUsers.size());
        log.info(">>>> 리미트 수 : {}",limit);

        if (filteredUsers.isEmpty()) {
            log.info(">>>> [디버깅 1.6] 차단 필터링 후 남은 후보가 없습니다.");
            return List.of();
        }

        List<Scored<User>> scored = new ArrayList<>();
        for (User cand : filteredUsers) {
            double s = score(me, cand, meAge, meLangs);
            scored.add(new Scored<>(cand, s));
        }

        log.info(">>>> [디버깅 2] 점수 계산 후 후보 수: {}", scored.size());

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        if (scored.size() <= limit) {
            log.info(">>>> [디버깅 3] 후보 수가 limit({}) 이하이므로 모두 반환합니다.", limit);
            return scored.stream()
                    .map(s -> toDto(s.getItem()))
                    .toList();
        }

        log.info(">>>> [디버깅 4] 후보 수가 충분하여 확률적 재랭킹을 시작합니다.");
        int poolK = Math.min(Math.max(limit * POOL_MULTIPLIER, MIN_POOL), scored.size());
        int finalLimit = Math.min(limit, poolK);
        List<User> chosen = pickGumbelTopK(scored.subList(0, poolK), finalLimit, TEMPERATURE);

        return chosen.stream().map(this::toDto).toList();
    }


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

    private double score(User me, User other, int meAge, Set<String> meLangs) {
        double purposeScore = eq(me.getPurpose(), other.getPurpose()) ? 1.0 : 0.0;
        double countryScore = eq(me.getCountry(), other.getCountry()) ? 1.0 : 0.0;
        double ageScore = ageSim(meAge, safeAge(other.getBirthdate()), 9.0);
        double langScore = jaccard(meLangs, csvToSet(other.getLanguage()));

        return W_PURPOSE * purposeScore
                + W_COUNTRY * countryScore
                + W_AGE * ageScore
                + W_LANG * langScore;
    }

    private double ageSim(int a, int b, double sigma) {
        if (a <= 0 || b <= 0) return 0.5;
        return Math.exp(-Math.abs(a - b) / sigma);
    }

    private boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private double jaccard(Set<String> A, Set<String> B) {
        if (A.isEmpty() && B.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(A);
        inter.retainAll(B);
        Set<String> uni = new HashSet<>(A);
        uni.addAll(B);
        return uni.isEmpty() ? 0.0 : (double) inter.size() / (double) uni.size();
    }

    private int safeAge(String birth) {
        if (birth == null || birth.isBlank()) {
            return -1;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            LocalDate d = LocalDate.parse(birth, formatter);
            return Period.between(d, LocalDate.now()).getYears();
        } catch (Exception e) {
            log.error(">>>> 잘못된 날짜 형식으로 나이 계산 실패: {}", birth, e);
            return -1;
        }
    }

    @Getter @AllArgsConstructor
    private static class Scored<T> { private T item; private double score; }


    private UserUpdateDTO toDto(User u) {
        String imageKey = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, u.getId())
                .map(image -> image.getUrl())
                .orElse(null);
        log.info("imageKey :{}",imageKey);

        return UserUpdateDTO.builder()
                .userId(u.getId())
                .firstname(u.getFirstName())
                .lastname(u.getLastName())
                .gender(u.getSex())
                .birthday(u.getBirthdate())
                .country(u.getCountry())
                .introduction(u.getIntroduction())
                .purpose(u.getPurpose())
                .language(csvToSet(u.getLanguage()).stream().toList())
                .hobby(csvToSet(u.getHobby()).stream().toList())
                .imageKey(imageKey)
                .build();
    }
    private boolean isBlocked(User me, User candidate) {
        return blockRepository.existsBlock(me.getId(), candidate.getId()) ||
                blockRepository.existsBlock(candidate.getId(), me.getId());
    }
}