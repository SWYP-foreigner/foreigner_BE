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
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentBasedRecommender {

    private final UserRepository userRepository;

    private static final double W_PURPOSE = 0.25;
    private static final double W_COUNTRY = 0.35;
    private static final double W_AGE     = 0.25;
    private static final double W_LANG    = 0.15;

    /**
     * meId 유저 기준 추천 상위 limit명
     */
    @Transactional(readOnly = true)
    public List<UserUpdateDTO> recommendForUser(Long meId, int limit) {
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        int meAge = safeAge(me.getBirthdate());              // "yyyy-MM-dd" 가정
        Set<String> meLangs = csvToSet(me.getLanguage());    // "en, ko" -> {"en","ko"}

        Page<User> page = userRepository.findCandidatesExcluding(
                meId, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "updatedAt"))
        );

        List<Scored<User>> scored = new ArrayList<>();
        for (User cand : page.getContent()) {
            double score = score(me, cand, meAge, meLangs);
            scored.add(new Scored<>(cand, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream()
                .limit(Math.max(1, limit))
                .map(s -> toDto(s.item))
                .toList();
    }


    private double score(User me, User other, int meAge, Set<String> meLangs) {
        double purposeScore = eq(me.getPurpose(), other.getPurpose()) ? 1.0 : 0.0;
        double countryScore = eq(me.getCountry(), other.getCountry()) ? 1.0 : 0.0;
        double ageScore     = ageSim(meAge, safeAge(other.getBirthdate()), 9.0); // sigma ~ 9
        double langScore    = jaccard(meLangs, csvToSet(other.getLanguage()));

        return W_PURPOSE * purposeScore
                + W_COUNTRY * countryScore
                + W_AGE     * ageScore
                + W_LANG    * langScore;
    }

    private double ageSim(int a, int b, double sigma) {
        if (a <= 0 || b <= 0) return 0.5; // 정보 없으면 중립값
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
        Set<String> inter = new HashSet<>(A); inter.retainAll(B);
        Set<String> uni   = new HashSet<>(A); uni.addAll(B);
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

    @Getter
    @AllArgsConstructor
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