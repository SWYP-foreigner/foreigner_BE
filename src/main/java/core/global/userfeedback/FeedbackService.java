package core.global.userfeedback;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.FeedbackSource;
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
     * 피드백 저장
     * 정책 변경: 중복 체크 로직 제거 (여러 번 제출 가능)
     */
    @Transactional
    public void createFeedback(Long userId, FeedbackRequest request) {
        User user = getUserOrThrow(userId);

        UserFeedback feedback = UserFeedback.builder()
                .user(user)
                .content(request.content())
                .source(FeedbackSource.valueOf(request.source()))
                .build();

        feedbackRepository.save(feedback);
    }

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}