package core.domain.maincontent.dto;

import core.domain.maincontent.entity.PollType;

import java.util.List;

public record PollResultResponse(
        Long pollId,
        PollType type,
        boolean isCorrect,      // 퀴즈일 경우 정답 여부
        Long correctOptionId,   // 퀴즈가 틀렸을 경우 정답 ID 안내
        List<OptionResult> results // 투표일 경우 각 항목의 득표율/득표수
) {
    public record OptionResult(
            Long optionId,
            long voteCount,
            double percentage // UI에 표시될 % (예: 46%)
    ) {}
}