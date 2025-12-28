package core.domain.maincontent.service;

import com.nimbusds.jose.proc.SecurityContext;
import core.domain.maincontent.dto.PollItem;
import core.domain.maincontent.dto.PollResultResponse;
import core.domain.maincontent.entity.Poll;
import core.domain.maincontent.entity.PollOption;
import core.domain.maincontent.entity.PollType;
import core.domain.maincontent.entity.VoteRecord;
import core.domain.maincontent.repository.PollOptionRepository;
import core.domain.maincontent.repository.PollRepository;
import core.domain.maincontent.repository.VoteRecordRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PollService {
    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final VoteRecordRepository voteRecordRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PollItem getTodayPoll(PollType type) {
        Poll poll = pollRepository.findFirstByTypeOrderByCreatedAtDesc(type)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POLL_NOT_FOUND));

        return mapToPollItem(poll);
    }

    private PollItem mapToPollItem(Poll poll) {
        List<PollItem.OptionItem> optionItems = poll.getOptions().stream()
                .map(opt -> new PollItem.OptionItem(opt.getId(), opt.getContent(), opt.getVoteCount()))
                .toList();

        return new PollItem(
                poll.getId(),
                poll.getType(),
                poll.getTitle(),
                "Tell me about your favorite content!", // 엔티티에 필드 추가 시 poll.getDescription()
                poll.getCloseAt(),
                poll.getTotalVoteCount(),
                optionItems,
                null // 실제 구현 시 SecurityContext에서 유저 확인 후 투표 여부 로직 추가
        );
    }

    @Transactional
    public PollResultResponse participate(Long pollId, Long optionId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 1. 투표 존재 확인 및 마감 여부 검증
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POLL_NOT_FOUND));

        if (poll.getCloseAt().isBefore(Instant.now())) {
            throw new BusinessException(CommunityErrorCode.POLL_ALREADY_CLOSED);
        }

        // 2. 중복 참여 확인
        if (voteRecordRepository.existsByUserAndPoll(user, poll)) {
            throw new BusinessException(CommunityErrorCode.ALREADY_PARTICIPATED);
        }

        // 3. 선택한 옵션 확인
        PollOption selectedOption = pollOptionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.OPTION_NOT_FOUND));

        // 4. 투표 기록 저장
        VoteRecord record = new VoteRecord(user, poll, selectedOption);
        voteRecordRepository.save(record);

        // 5. 카운트 업데이트 (Denormalization 필드)
        poll.incrementTotalCount(); // poll.totalVoteCount++
        selectedOption.incrementVoteCount(); // option.voteCount++

        // 6. 결과 반환 처리
        boolean isCorrect = false;
        Long correctOptionId = null;

        if (poll.getType() == PollType.QUIZ) {
            isCorrect = selectedOption.isCorrect();
            if (!isCorrect) {
                // 틀렸을 경우 정답 ID를 찾아줌
                correctOptionId = poll.getOptions().stream()
                        .filter(PollOption::isCorrect)
                        .map(PollOption::getId)
                        .findFirst().orElse(null);
            }
        }

        List<PollResultResponse.OptionResult> results = poll.getOptions().stream()
                .map(opt -> new PollResultResponse.OptionResult(
                        opt.getId(),
                        opt.getVoteCount(),
                        poll.calculatePercentage(opt.getVoteCount()) // 백분율 계산 로직
                )).toList();

        return new PollResultResponse(poll.getId(), poll.getType(), isCorrect, correctOptionId, results);
    }
}
