package core.global.userfeedback;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.FeedbackErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.userfeedback.dto.FeedbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackService {

    private final UserFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    /**
     * 피드백 대상 여부 확인
     * 이미 피드백을 작성한 유저라면 false 반환
     */
    public boolean checkEligibility(Long userId) {
        User user = getUserOrThrow(userId);
        return !feedbackRepository.existsByUser(user);
    }

    /**
     * 피드백 저장
     */
    @Transactional
    public void createFeedback(Long userId, FeedbackRequest request) {
        User user = getUserOrThrow(userId);

        if (feedbackRepository.existsByUser(user)) {
            throw new BusinessException(FeedbackErrorCode.ALREADY_SUBMITTED);
        }

        UserFeedback feedback = UserFeedback.builder()
                .user(user)
                .content(request.content())
                .source(request.source())
                .build();

        feedbackRepository.save(feedback);
    }

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}