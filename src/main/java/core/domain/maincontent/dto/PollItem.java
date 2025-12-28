package core.domain.maincontent.dto;

import core.domain.maincontent.entity.PollType;

import java.time.Instant;
import java.util.List;

public record PollItem(
        Long id,
        PollType type,
        String title,       // Q. 질문 내용
        String description, // 하단 설명 (Tell me about...)
        Instant closeAt,
        long totalVoteCount,
        List<OptionItem> options,
        Long selectedOptionId // 현재 로그인한 사용자가 참여했다면 해당 ID
) {
    public record OptionItem(
            Long id,
            String content,
            long voteCount
    ) {}
}