package core.domain.user.service;

import core.domain.user.dto.CommendUsersProfileResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommenderService {

    private final UserRepository userRepository;
    private final ContentBasedRecommender recommender;

    /**
     * 인증 객체에서 이메일을 추출하여 사용자 ID를 얻고 추천 로직을 실행합니다.
     * @param limit 추천받을 사용자 수
     * @return 추천된 사용자 목록
     */
    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(int limit) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();

        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        return recommender.recommendForUser(me.getId(), 3);
    }



}
