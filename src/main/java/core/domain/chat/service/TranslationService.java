package core.domain.chat.service;


import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TranslationService {

    @Value("${google.cloud.project.id}")
    private String projectId;

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
                    .map(translation -> translation.getTranslatedText())
                    .collect(Collectors.toList());

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("번역 서비스 오류가 발생했습니다.", e);
        }
    }
}