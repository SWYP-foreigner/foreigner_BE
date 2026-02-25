package core.domain.admin.service;

import core.domain.admin.dto.PerspectiveRequest;
import core.domain.admin.dto.PerspectiveResponse;
import core.global.service.TranslationService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerspectiveService {

    private final RestTemplate restTemplate;
    private final TranslationService translationService;

    @Value("${google.cloud.perspective.api-key}")
    private String apiKey;

    private static final String API_URL = "https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze?key=";
    public static final double SPAM_THRESHOLD = 0.9;
    public static final double TOXICITY_THRESHOLD = 0.8;

    /**
     * 서킷 브레이커와 타임리미터를 적용하여 외부 API 장애가 서버 전체로 퍼지는 것을 방지합니다.
     */
    @CircuitBreaker(name = "spamCheck", fallbackMethod = "spamCheckFallback")
    @TimeLimiter(name = "spamCheck")
    public boolean isHarmful(String text) {
        if (text == null || text.isBlank()) return false;

        // 1. 분석을 위해 텍스트 준비 (필요 시 영어로 번역)
        String textToAnalyze = prepareTextForAnalysis(text);

        // 2. API 호출 및 점수 검사
        return checkScoresViaApi(textToAnalyze, text);
    }

    private String prepareTextForAnalysis(String text) {
        try {
            String lang = translationService.detectLanguage(text);
            if ("en".equalsIgnoreCase(lang)) return text;
            return translationService.translatePost(text, "en");
        } catch (Exception e) {
            log.warn("언어 감지/번역 실패, 원문으로 진행: {}", e.getMessage());
            return text;
        }
    }

    private boolean checkScoresViaApi(String textToAnalyze, String originalText) {
        PerspectiveRequest requestBody = PerspectiveRequest.forCheck(textToAnalyze, Map.of(
                "SPAM", new PerspectiveRequest.ScoreThreshold(),
                "TOXICITY", new PerspectiveRequest.ScoreThreshold()
        ));

        try {
            PerspectiveResponse response = restTemplate.postForObject(API_URL + apiKey, new HttpEntity<>(requestBody), PerspectiveResponse.class);
            if (response == null || response.attributeScores() == null) return false;

            double spam = getScore(response, "SPAM");
            double toxicity = getScore(response, "TOXICITY");

            log.info("스팸 검사 완료 - SPAM: {}, TOXICITY: {}", spam, toxicity);
            return spam >= SPAM_THRESHOLD || toxicity >= TOXICITY_THRESHOLD;
        } catch (Exception e) {
            log.error("Perspective API 호출 실패: {}", e.getMessage());
            return false;
        }
    }

    private double getScore(PerspectiveResponse response, String type) {
        return response.attributeScores().getOrDefault(type,
                        new PerspectiveResponse.AttributeScore(new PerspectiveResponse.SummaryScore(0.0)))
                .summaryScore().value();
    }

    /**
     * API 장애 또는 2초 타임아웃 발생 시 실행되는 안전장치
     */
    public boolean spamCheckFallback(String text, Throwable t) {
        log.error("Perspective API 장애/타임아웃! 검사를 생략합니다. 사유: {}", t.getMessage());
        return false; // 장애 시에는 '정상 메시지'로 처리하여 채팅 중단을 막음
    }
}