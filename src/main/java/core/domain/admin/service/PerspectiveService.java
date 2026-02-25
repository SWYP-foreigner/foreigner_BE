package core.domain.admin.service;

import com.nimbusds.oauth2.sdk.util.StringUtils;
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
import java.util.Optional;

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
    private static final String DEFAULT_LANG_EN = "en";
    /**
     * 유해 콘텐츠 여부 판별 (Circuit Breaker & Time Limiter 적용)
     */
    @CircuitBreaker(name = "spamCheck", fallbackMethod = "spamCheckFallback")
    @TimeLimiter(name = "spamCheck")
    public boolean isHarmful(String text) {
        if (StringUtils.isBlank(text)) return false;

        String analyzedText = prepareAnalysisText(text);
        PerspectiveResponse response = requestPerspectiveAnalysis(analyzedText);

        return isExceedingThreshold(response, text);
    }

    /**
     * 분석에 적합한 텍스트로 가공 (영어 권장)
     */
    private String prepareAnalysisText(String text) {
        try {
            String detectedLang = translationService.detectLanguage(text);
            if (DEFAULT_LANG_EN.equalsIgnoreCase(detectedLang)) return text;

            return translationService.translatePost(text, DEFAULT_LANG_EN);
        } catch (Exception e) {
            log.warn("분석 전 언어 처리 실패, 원문 사용: {}", e.getMessage());
            return text;
        }
    }

    /**
     * Perspective API 실제 호출
     */
    private PerspectiveResponse requestPerspectiveAnalysis(String text) {
        PerspectiveRequest request = PerspectiveRequest.forCheck(text, Map.of(
                "SPAM", new PerspectiveRequest.ScoreThreshold(),
                "TOXICITY", new PerspectiveRequest.ScoreThreshold()
        ));

        try {
            return restTemplate.postForObject(API_URL + apiKey, new HttpEntity<>(request), PerspectiveResponse.class);
        } catch (Exception e) {
            log.error("Perspective API 호출 중 예외 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 응답 점수가 임계치를 초과했는지 판별
     */
    private boolean isExceedingThreshold(PerspectiveResponse response, String originalText) {
        if (response == null || response.attributeScores() == null) return false;

        double spamScore = extractScore(response, "SPAM");
        double toxicityScore = extractScore(response, "TOXICITY");

        log.info("검사 완료(Score): SPAM={}, TOXICITY={}", spamScore, toxicityScore);
        return spamScore >= SPAM_THRESHOLD || toxicityScore >= TOXICITY_THRESHOLD;
    }

    private double extractScore(PerspectiveResponse response, String attributeType) {
        return Optional.ofNullable(response.attributeScores().get(attributeType))
                .map(score -> score.summaryScore().value())
                .orElse(0.0);
    }

    /**
     * 장애 발생 시 Fallback: 서비스 연속성을 위해 '정상' 반환
     */
    public boolean spamCheckFallback(String text, Throwable t) {
        log.error("Perspective API 장애/지연 발생 - 검사 건너뜀 [사유: {}]", t.getMessage());
        return false;
    }
}