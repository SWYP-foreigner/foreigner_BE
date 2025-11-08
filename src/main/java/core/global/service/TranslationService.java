package core.global.service;

import com.google.cloud.translate.v3.*;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
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

    public List<String> translateMessages(List<String> messages, String targetLanguage) {
        if (messages == null || messages.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return messages;
        }

         try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, "global");

            TranslateTextRequest request = TranslateTextRequest.newBuilder()
                    .setParent(parent.toString())
                    .setMimeType("text/plain")
                    .setTargetLanguageCode(targetLanguage)
                    .addAllContents(messages)
                    .build();

            TranslateTextResponse response = client.translateText(request);

            return response.getTranslationsList().stream()
                    .map(Translation::getTranslatedText)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error(">>>> [GOOGLE_TRANSLATE_API_ERROR] Google 번역 API 호출 실패! 상세 원인: ", e);
            throw new BusinessException(
                    ErrorCode.TRANSLATE_FAIL.getErrorCode(),
                    ErrorCode.TRANSLATE_FAIL,
                    ErrorCode.TRANSLATE_FAIL.getMessage(),
                    e
            );
        }
    }


    @Transactional
    public void saveUserLanguage(Authentication auth, String language) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (language != null && !language.isEmpty()) {
            user.updateTranslateLanguage(language);
        }
        userRepository.save(user);
        log.info("사용자 언어 및 번역 언어 저장 완료: userId={}, language={}, translateLanguage={}",
                user.getId(), user.getLanguage(), user.getTranslateLanguage());
    }

    public String translatePost(String post, String targetLanguage) {
        if (post == null || post.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return post;
        }

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, "global");

            TranslateTextRequest request = TranslateTextRequest.newBuilder()
                    .setParent(parent.toString())
                    .setMimeType("text/plain")
                    .setTargetLanguageCode(targetLanguage)
                    .addContents(post)
                    .build();

            TranslateTextResponse response = client.translateText(request);

            return response.getTranslationsList().get(0).getTranslatedText();

        } catch (Exception e) {
            log.error(">>>> [GOOGLE_TRANSLATE_API_ERROR] Google 번역 API 호출 실패! 상세 원인: ", e);
            throw new BusinessException(
                    ErrorCode.TRANSLATE_FAIL.getErrorCode(),
                    ErrorCode.TRANSLATE_FAIL,
                    ErrorCode.TRANSLATE_FAIL.getMessage(),
                    e
            );
        }
    }


    public List<String> translateComments(List<String> comments, String targetLanguage) {
        if (comments == null || comments.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return comments;
        }

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, "global");

            TranslateTextRequest request = TranslateTextRequest.newBuilder()
                    .setParent(parent.toString())
                    .setMimeType("text/plain")
                    .setTargetLanguageCode(targetLanguage)
                    .addAllContents(comments)
                    .build();

            TranslateTextResponse response = client.translateText(request);

            return response.getTranslationsList().stream()
                    .map(Translation::getTranslatedText)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error(">>>> [GOOGLE_TRANSLATE_API_ERROR] Google 번역 API 호출 실패! 상세 원인: ", e);
            throw new BusinessException(
                    ErrorCode.TRANSLATE_FAIL.getErrorCode(),
                    ErrorCode.TRANSLATE_FAIL,
                    ErrorCode.TRANSLATE_FAIL.getMessage(),
                    e
            );
        }
    }

    public String detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return "und";
        }

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, "global");

            DetectLanguageRequest request =
                    DetectLanguageRequest.newBuilder()
                            .setParent(parent.toString())
                            .setMimeType("text/plain")
                            .setContent(text)
                            .build();

            DetectLanguageResponse response = client.detectLanguage(request);

            // 가장 확률이 높은 언어 반환
            if (response.getLanguagesCount() > 0) {
                String langCode = response.getLanguages(0).getLanguageCode();
                log.debug("감지된 언어: {} (내용: {}...)", langCode, text.substring(0, Math.min(text.length(), 20)));
                return langCode;
            }
            return "und"; // 감지 실패

        } catch (Exception e) {
            log.error(">>>> [GOOGLE_TRANSLATE_API_ERROR] Google 언어 감지 API 호출 실패!", e);
            throw new BusinessException(
                    ErrorCode.TRANSLATE_FAIL.getErrorCode(),
                    ErrorCode.TRANSLATE_FAIL,
                    "언어 감지에 실패했습니다.",
                    e
            );
        }
    }
}