package core.global.service;

import core.global.dto.PerspectiveRequest;
import core.global.dto.PerspectiveResponse;
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

    public boolean isHarmful(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String textToAnalyze = text;
        String detectedLanguage = "und";

        try {
            detectedLanguage = translationService.detectLanguage(text);

            if (!"en".equalsIgnoreCase(detectedLanguage)) {
                log.debug("언어 감지: {} -> 영어로 번역 시도.", detectedLanguage);
                textToAnalyze = translationService.translatePost(text, "en");
            } else {
                log.debug("언어 감지: en. 번역 없이 진행.");
            }
        } catch (Exception e) {
            log.error("Perspective 검사 전 번역/언어감지 실패: {}", e.getMessage());
            return false;
        }

        Map<String, PerspectiveRequest.ScoreThreshold> attributesToRequest = Map.of(
                "SPAM", new PerspectiveRequest.ScoreThreshold(),
                "TOXICITY", new PerspectiveRequest.ScoreThreshold(),
                "SEXUALLY_EXPLICIT", new PerspectiveRequest.ScoreThreshold(),
                "THREAT", new PerspectiveRequest.ScoreThreshold()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        PerspectiveRequest requestBody = PerspectiveRequest.forCheck(textToAnalyze, attributesToRequest);
        HttpEntity<PerspectiveRequest> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            PerspectiveResponse response = restTemplate.postForObject(
                    API_URL + apiKey,
                    requestEntity,
                    PerspectiveResponse.class
            );

            if (response == null || response.attributeScores() == null) {
                log.warn("Perspective API 응답이 비정상적임.");
                return false;
            }

            double spamScore = response.attributeScores().getOrDefault("SPAM",
                            new PerspectiveResponse.AttributeScore(new PerspectiveResponse.SummaryScore(0.0)))
                    .summaryScore().value();

            double toxicityScore = response.attributeScores().getOrDefault("TOXICITY",
                            new PerspectiveResponse.AttributeScore(new PerspectiveResponse.SummaryScore(0.0)))
                    .summaryScore().value();

            log.info("Perspective API 점수 (Lang: {}->en, Text: {}...): SPAM={}, TOXICITY={}",
                    detectedLanguage, text.substring(0, Math.min(text.length(), 20)), spamScore, toxicityScore);

            return spamScore >= SPAM_THRESHOLD || toxicityScore >= TOXICITY_THRESHOLD;

        } catch (Exception e) {
            log.error("Perspective API 호출 실패: {}", e.getMessage());
            return false;
        }
    }
}