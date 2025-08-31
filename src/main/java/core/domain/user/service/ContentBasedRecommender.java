package core.domain.user.service;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class ContentBasedRecommender {

    private final UserRepository userRepository;

    private static final double W_PURPOSE = 0.4;
    private static final double W_COUNTRY = 0.2;
    private static final double W_AGE = 0.3;   // 너가 바꾼 가중치 유지
    private static final double W_LANG = 0.1;  // 너가 바꾼 가중치 유지

    /**
     랜덤 랭킹용 파라미터 (원하면 @Value 로 빼서 설정 가능)
     * POOL_MULTIPLIER = 5;
     * (limit * 5) 풀에서 추출
     *  MIN_POOL:최소 풀 사이즈
     *  TEMPERATURE: 클수록 랜덤성 증가
     */
    private static final int   POOL_MULTIPLIER = 3;
    private static final int   MIN_POOL       = 50;
    private static final double TEMPERATURE   = 0.1;

    private static final java.security.SecureRandom RAND = new java.security.SecureRandom();

    @Transactional(readOnly = true)
    public List<UserUpdateDTO> recommendForUser(Long meId, int limit) {
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        int meAge = safeAge(me.getBirthdate());
        Set<String> meLangs = csvToSet(me.getLanguage());

        Page<User> page = userRepository.findCandidatesExcluding(
                meId, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "updatedAt"))
        );

        List<Scored<User>> scored = new ArrayList<>();
        for (User cand : page.getContent()) {
            double s = score(me, cand, meAge, meLangs);
            scored.add(new Scored<>(cand, s));
        }

        /**
         1) 규칙대로 점수 계산 후 내림차순 정렬(기본 베이스)
          */
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 2) 상위 풀에서 Gumbel-Top-k로 확률적 재랭킹 → 호출마다 순서 달라짐
        int poolK = Math.min(Math.max(limit * POOL_MULTIPLIER, MIN_POOL), scored.size());
        List<User> chosen = pickGumbelTopK(scored.subList(0, poolK), limit, TEMPERATURE);

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
        double ageScore     = ageSim(meAge, safeAge(other.getBirthdate()), 9.0);
        double langScore    = jaccard(meLangs, csvToSet(other.getLanguage()));

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
        try {
            LocalDate d = LocalDate.parse(birth);
            return Period.between(d, LocalDate.now()).getYears();
        } catch (Exception e) {
            return -1;
        }
    }

    @Getter @AllArgsConstructor
    private static class Scored<T> { private T item; private double score; }

    private UserUpdateDTO toDto(User u) {
        return UserUpdateDTO.builder()
                .firstname(u.getFirstName())
                .lastname(u.getLastName())
                .gender(u.getSex())
                .birthday(u.getBirthdate())
                .country(u.getCountry())
                .introduction(u.getIntroduction())
                .purpose(u.getPurpose())
                .language(csvToSet(u.getLanguage()).stream().toList())
                .hobby(csvToSet(u.getHobby()).stream().toList())
                .imageKey(null)
                .build();
    }
}
