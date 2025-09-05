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
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

        // 라이브러리가 GOOGLE_APPLICATION_CREDENTIALS 환경 변수를 자동으로 읽어 인증합니다.
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
        log.info("여기까지 옴 ,{} " ,auth.getName());
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)); // 사용자 엔티티를 찾지 못할 경우 예외 처리

        user.setLanguage(language);
        userRepository.save(user);
    }
}