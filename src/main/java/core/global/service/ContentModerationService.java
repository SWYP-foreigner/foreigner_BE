package core.global.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentModerationService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${sightengine.api-user}")
    private String apiUser;

    @Value("${sightengine.api-secret}")
    private String apiSecret;

    private static final String MODELS = "nudity,wad,offensive,gore";
    private static final String API_URL = "https://api.sightengine.com/1.0/check.json";
    private static final double THRESHOLD = 0.85;
    private static final double NUDITY_THRESHOLD = 0.1;

    public ModerationResult inspectImage(MultipartFile file) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("models", MODELS);
            body.add("api_user", apiUser);
            body.add("api_secret", apiSecret);
            body.add("media", new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() { return file.getOriginalFilename(); }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(API_URL, requestEntity, String.class);

            return analyzeResponse(response);

        } catch (Exception e) {
            log.error("Sightengine API 호출 실패 (안전으로 간주)", e);
            return new ModerationResult(false, "API Error");
        }
    }

    private ModerationResult analyzeResponse(String jsonResponse) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        String status = root.path("status").asText();

        if (!"success".equals(status)) return new ModerationResult(false, "API Error");

        double nudityRaw = root.path("nudity").path("raw").asDouble(0.0);
        double nudityPartial = root.path("nudity").path("partial").asDouble(0.0);
        double nuditySafe = root.path("nudity").path("safe").asDouble(1.0);

        double weapon = root.path("weapon").asDouble(0.0);
        double alcohol = root.path("alcohol").asDouble(0.0);
        double drugs = root.path("drugs").asDouble(0.0);
        double offensive = root.path("offensive").path("prob").asDouble(0.0);
        double gore = root.path("gore").path("prob").asDouble(0.0);

        boolean isHarmful = false;
        StringBuilder reason = new StringBuilder();

        if (nudityRaw > THRESHOLD || nudityPartial > THRESHOLD || nuditySafe < NUDITY_THRESHOLD) {
            isHarmful = true;
            double maxNudity = Math.max(nudityRaw, nudityPartial);
            reason.append(String.format("[Nudity: %.2f(Raw:%.2f/Part:%.2f)] ", maxNudity, nudityRaw, nudityPartial));
        }

        if (weapon > THRESHOLD) {
            isHarmful = true;
            reason.append(String.format("[Weapon: %.2f] ", weapon));
        }
        if (alcohol > THRESHOLD) {
            isHarmful = true;
            reason.append(String.format("[Alcohol: %.2f] ", alcohol));
        }
        if (drugs > THRESHOLD) {
            isHarmful = true;
            reason.append(String.format("[Drugs: %.2f] ", drugs));
        }

        if (offensive > THRESHOLD) {
            isHarmful = true;
            reason.append(String.format("[Offensive: %.2f] ", offensive));
        }
        if (gore > THRESHOLD) {
            isHarmful = true;
            reason.append(String.format("[Gore: %.2f] ", gore));
        }

        if (isHarmful) {
            log.warn("🚨 유해 이미지 감지됨: {}", reason);
            return new ModerationResult(true, reason.toString().trim());
        }

        log.info("✅ 이미지 검사 통과 [Safe: {}, Raw: {}, Partial: {}]",
                String.format("%.2f", nuditySafe),
                String.format("%.2f", nudityRaw),
                String.format("%.2f", nudityPartial));

        return new ModerationResult(false, null);
    }

    @Getter
    public static class ModerationResult {
        private final boolean harmful;
        private final String reason;

        public ModerationResult(boolean harmful, String reason) {
            this.harmful = harmful;
            this.reason = reason;
        }
    }
}
