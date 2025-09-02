package core.domain.chat.service;

import com.google.cloud.translate.v3.*;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TranslationService {

    @Value("${google.cloud.project.id}")
    private String projectId;

    @Value("${google.cloud.translate.api-key}")
    private String apiKey;

    /**
     * 지정된 언어로 메시지 목록을 번역합니다.
     *
     * @param messages 번역할 메시지 원문 목록.
     * @param targetLanguage 번역할 대상 언어 코드 (예: "ko", "en").
     * @return 번역된 메시지 목록.
     */
    public List<String> translateMessages(List<String> messages, String targetLanguage) {
        if (messages == null || messages.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return messages;
        }
        log.info(">>>> [TRANSLATION_DATA_CHECK] Target Language: '{}'", targetLanguage);
        log.info(">>>> [TRANSLATION_DATA_CHECK] Messages to Translate: {}", messages);
        try {
            TranslationServiceSettings settings = TranslationServiceSettings.newBuilder()
                    .setHeaderProvider(() -> Collections.singletonMap("x-goog-api-key", apiKey))
                    .build();

            try (TranslationServiceClient client = TranslationServiceClient.create(settings)) {
                LocationName parent = LocationName.of(projectId, "global");
                log.info(">>>> [TRANSLATION_DATA_CHECK] Target Language: '{}'", targetLanguage);
                log.info(">>>> [TRANSLATION_DATA_CHECK] Messages to Translate: {}", messages);
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
            }

        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.TRANSLATE_FAIL.getErrorCode(),
                    ErrorCode.TRANSLATE_FAIL,
                    ErrorCode.TRANSLATE_FAIL.getMessage(),
                    e
            );
        }
    }
}