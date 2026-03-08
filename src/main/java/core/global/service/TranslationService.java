package core.global.service;

import com.google.cloud.translate.v3.*;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
@Service
@Slf4j
@RequiredArgsConstructor
public class TranslationService {

    @Value("${google.cloud.project.id}")
    private String projectId;
    private final UserRepository userRepository;

    @Value("${google.translate.api-url:https://taylor-easternmost-temple.ngrok-free.dev/v3/projects/any-id/locations/global:translateText}")
    private String mockApiUrl;

    // HTTP 요청을 위한 RestTemplate (Bean으로 등록해서 써도 됩니다)
    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

    public List<String> translateMessages(List<String> messages, String targetLanguage) {
        if (messages == null || messages.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return messages;
        }

        try {
            // 1. Mock 서버(FastAPI) 규격에 맞는 요청 바디 생성
            java.util.Map<String, Object> requestBody = java.util.Map.of(
                    "contents", messages,
                    "targetLanguageCode", targetLanguage
            );

            // 2. ngrok 경고 페이지 우회를 위한 헤더 설정
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("ngrok-skip-browser-warning", "69420");

            org.springframework.http.HttpEntity<java.util.Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(requestBody, headers);

            // 3. Mock 서버 호출 (POST)
            org.springframework.http.ResponseEntity<java.util.Map> response =
                    restTemplate.postForEntity(mockApiUrl, entity, java.util.Map.class);

            // 4. FastAPI가 준 응답에서 번역 텍스트만 추출
            List<java.util.Map<String, String>> translations =
                    (List<java.util.Map<String, String>>) response.getBody().get("translations");

            return translations.stream()
                    .map(t -> t.get("translatedText"))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error(">>>> [MOCK_TRANSLATION_ERROR] Mock 서버 호출 실패! 상세 원인: ", e);
            // 테스트 중단 방지를 위해 실패 시 원문 반환
            return messages;
        }
    }

    // translatePost와 translateComments도 위 translateMessages를 재사용하도록 수정
    public String translatePost(String post, String targetLanguage) {
        List<String> results = translateMessages(List.of(post), targetLanguage);
        return results.get(0);
    }

    public List<String> translateComments(List<String> comments, String targetLanguage) {
        return translateMessages(comments, targetLanguage);
    }

    public String detectLanguage(String text) {
        // 언어 감지는 비용이 적으니 그대로 두셔도 되고,
        // 필요하다면 Mock 서버에 /detect 경로를 만들어서 비슷하게 처리하세요.
        return "en"; // 테스트용 고정 응답
    }

    @Transactional
    public void saveUserLanguage(Authentication auth, String language) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (language != null && !language.isEmpty()) {
            user.updateTranslateLanguage(language);
        }
        userRepository.save(user);
    }
}