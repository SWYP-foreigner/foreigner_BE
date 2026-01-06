package core.domain.maincontent.service.search;

import core.global.service.SimpleKeywordExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MainContentKeywordExtractor {

    private final SimpleKeywordExtractor baseExtractor;

    /**
     * 뉴스 본문(HTML)에서 깨끗한 키워드만 추출
     */
    public List<String> extract(String htmlContent, int maxCandidates) {
        if (htmlContent == null || htmlContent.isBlank()) return List.of();

        // 1. 노이즈 제거 (HTML 태그, URL, 특수 엔티티)
        String cleanText = preProcess(htmlContent);

        // 2. 정제된 텍스트에서 키워드 추출 (기존 로직 재활용)
        return baseExtractor.extract(cleanText, maxCandidates);
    }

    private String preProcess(String html) {
        // HTML 태그 제거
        String text = html.replaceAll("<[^>]*>", " ");

        // URL 제거 (http, https 등)
        text = text.replaceAll("(http|https|ftp)://[^\\s/$.?#].[^\\s]*", " ");

        // HTML 엔티티 제거 (예: &nbsp;, &gt; 등)
        text = text.replaceAll("&[a-zA-Z0-9#]+;", " ");

        // 연속된 공백 하나로 통합
        return text.replaceAll("\\s+", " ").trim();
    }
}