package core.global.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
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

    // 차단 기준값 (Standard 모드 기준)
    private static final double THRESHOLD = 0.85;
    private static final double NUDITY_THRESHOLD = 0.1;

    public void inspectImage(MultipartFile file) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("models", MODELS);
            body.add("api_user", apiUser);
            body.add("api_secret", apiSecret);
            body.add("media", new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(API_URL, requestEntity, String.class);

            analyzeResponse(response);

        } catch (IOException e) {
            log.error("이미지 검사 중 파일 읽기 오류", e);
            // [수정] ImageErrorCode 사용 (이미지 처리 중 오류)
            throw new BusinessException(ImageErrorCode.IMAGE_PROCESSING_FAILED);
        } catch (BusinessException e) {
            throw e; // 이미 발생한 비즈니스 예외는 그대로 던짐
        } catch (Exception e) {
            log.error("Sightengine API 호출 실패", e);
            // API 장애 시 로그만 남기고 통과시킴 (서비스 중단 방지)
        }
    }

    private void analyzeResponse(String jsonResponse) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        String status = root.path("status").asText();

        if (!"success".equals(status)) {
            log.warn("Content moderation API error: {}", jsonResponse);
            return;
        }

        // 1. 누드 체크
        double nudityRaw = root.path("nudity").path("raw").asDouble(0.0);
        double nuditySafe = root.path("nudity").path("safe").asDouble(1.0);

        // [수정] CommunityErrorCode 사용 (정책 위반)
        if (nudityRaw > THRESHOLD || nuditySafe < NUDITY_THRESHOLD) {
            throw new BusinessException(CommunityErrorCode.INAPPROPRIATE_CONTENT);
        }

        // 2. 무기/폭력/마약
        double weapon = root.path("weapon").asDouble(0.0);
        double alcohol = root.path("alcohol").asDouble(0.0);
        double drugs = root.path("drugs").asDouble(0.0);

        if (weapon > THRESHOLD || alcohol > THRESHOLD || drugs > THRESHOLD) {
            throw new BusinessException(CommunityErrorCode.INAPPROPRIATE_CONTENT);
        }

        // 3. 혐오/모욕
        double offensive = root.path("offensive").path("prob").asDouble(0.0);
        if (offensive > THRESHOLD) {
            throw new BusinessException(CommunityErrorCode.INAPPROPRIATE_CONTENT);
        }

        // 4. 고어
        double gore = root.path("gore").path("prob").asDouble(0.0);
        if (gore > THRESHOLD) {
            throw new BusinessException(CommunityErrorCode.INAPPROPRIATE_CONTENT);
        }

        log.info("Image moderation passed. (Safe Score: {})", nuditySafe);
    }
}
