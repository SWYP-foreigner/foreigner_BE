package core.global.search;

public record SearchResult(
        PostDocument doc,
        double score,
        String highlight // content 하이라이트 1개 조각
) {}
