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
public class FeedbackService {

    private final UserFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;


    /**
     * 피드백 저장
     * 정책 변경: 중복 체크 로직 제거 (여러 번 제출 가능)
     * 수정 사항: String 입력값을 대소문자 구분 없이 Enum으로 변환 처리
     */
    @Transactional
    public void createFeedback(Long userId, FeedbackRequest request) {
        User user = getUserOrThrow(userId);
        FeedbackSource feedbackSource;
        try {
            feedbackSource = FeedbackSource.valueOf(request.source().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(UserErrorCode.INVALID_INPUT_VALUE);
        }

        UserFeedback feedback = UserFeedback.builder()
                .user(user)
                .content(request.content())
                .source(feedbackSource)
                .build();

        feedbackRepository.save(feedback);
    }

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}