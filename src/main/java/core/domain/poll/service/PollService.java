package core.domain.poll.service;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.poll.dto.VoteWriteRequest;
import core.domain.poll.dto.PollItem;
import core.domain.poll.dto.PollResultResponse;
import core.domain.poll.dto.QuizWriteRequest;
import core.domain.poll.entity.Poll;
import core.domain.poll.entity.PollOption;
import core.domain.poll.entity.VoteRecord;
import core.domain.poll.repository.PollOptionRepository;
import core.domain.poll.repository.PollRepository;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.BoardCategory;
import core.global.enums.PollType;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final VoteRecordRepository voteRecordRepository;
    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final PostRepository postRepository;

    @Transactional(readOnly = true)
    public PollItem getTodayPoll(PollType type) {
        Poll poll = pollRepository.findLatestPollByType(type)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POLL_NOT_FOUND));

        Long selectedOptionId = getCurrentUser()
                .flatMap(user -> voteRecordRepository.findByUserIdAndPollId(user.getId(), poll.getId()))
                .map(recordResult -> recordResult.getPollOption().getId())
                .orElse(null);

        Long correctOptionId = null;
        if (selectedOptionId != null) {
            correctOptionId = pollOptionRepository.findCorrectOptionId(poll.getId())
                    .orElse(null);
        }

        return mapToPollItem(poll, selectedOptionId, correctOptionId);
    }

    private PollItem mapToPollItem(Poll poll, Long selectedOptionId, Long correctOptionId) {
        List<PollItem.OptionItem> optionItems = poll.getOptions().stream()
                .map(opt -> new PollItem.OptionItem(opt.getId(), opt.getContent(), opt.getVoteCount()))
                .toList();

        return new PollItem(
                poll.getId(),
                poll.getType(),
                poll.getTitle(),
                poll.getDescription(),
                poll.getCloseAt(),
                poll.getTotalVoteCount(),
                optionItems,
                selectedOptionId,
                correctOptionId
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

        if (poll.isClosed()) {
            throw new BusinessException(CommunityErrorCode.POLL_ALREADY_CLOSED);
        }

        // 2. 중복 참여 확인
        if (voteRecordRepository.existsByUserAndPoll(user, poll)) {
            throw new BusinessException(CommunityErrorCode.ALREADY_PARTICIPATED);
        }

        // 3. 선택한 옵션 확인
        PollOption selectedOption = pollOptionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.OPTION_NOT_FOUND));

        if (!selectedOption.getPoll().getId().equals(pollId)) {
            throw new BusinessException(CommunityErrorCode.INVALID_INPUT);
        }

        // 4. 투표 기록 저장
        VoteRecord recordResult = new VoteRecord(user, poll, selectedOption);
        voteRecordRepository.save(recordResult);

        // 5. 카운트 업데이트 (Denormalization 필드)
        poll.incrementTotalCount(); // poll.totalVoteCount++
        selectedOption.incrementVoteCount(); // option.voteCount++

        // 6. 결과 반환 처리
        return createPollResultResponse(poll, selectedOption);
    }

    private PollResultResponse createPollResultResponse(Poll poll, PollOption selectedOption) {
        Boolean isCorrect = false;
        Long correctOptionId = null;

        if (poll.getType() == PollType.QUIZ) {
            isCorrect = selectedOption.getIsCorrect();
            if (!isCorrect) {
                correctOptionId = poll.getOptions().stream()
                        .filter(PollOption::getIsCorrect)
                        .map(PollOption::getId)
                        .findFirst().orElse(null);
            }
        }

        List<PollResultResponse.OptionResult> results = poll.getOptions().stream()
                .map(opt -> new PollResultResponse.OptionResult(
                        opt.getId(),
                        opt.getVoteCount(),
                        poll.calculatePercentage(opt.getVoteCount())
                )).toList();

        return new PollResultResponse(poll.getId(), poll.getType(), isCorrect, correctOptionId, results);
    }

    @Transactional
    public Long createVote(VoteWriteRequest request) {
        User user = getCurrentUser()
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Board board = boardRepository.findByCategory(BoardCategory.VOTE)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        // 1. 마스터 Post 엔티티 먼저 생성 (익명 여부 반영)
        Post post = new Post(request.content(), user, board, request.isAnonymous());

        // 2. Poll 엔티티 생성 (생성자 호출 시 내부에서 post와 연결됨)
        new Poll(user, post, request);

        // 3. Post만 저장하면 CascadeType.ALL에 의해 Poll까지 한 번에 저장됨
        return postRepository.save(post).getId();
    }

    @Transactional
    public Long createQuiz(QuizWriteRequest request) {
        User user = getCurrentUser()
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Board board = boardRepository.findByCategory(BoardCategory.QUIZ)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        // 1. Post 엔티티 생성 (퀴즈는 기본적으로 익명 false)
        Post post = new Post(request.content(), user, board, false);

        // 2. Poll 엔티티 생성
        new Poll(user, post, request);

        // 3. 저장
        return postRepository.save(post).getId();
    }

    private Optional<User> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        if (email == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
    }

}
