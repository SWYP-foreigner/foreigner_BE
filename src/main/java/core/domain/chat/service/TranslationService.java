package core.domain.chat.service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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





}