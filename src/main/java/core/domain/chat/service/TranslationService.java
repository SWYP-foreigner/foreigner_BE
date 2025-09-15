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



    @Transactional
    public void saveUserLanguage(Authentication auth, String language) {
        log.info("인증된 사용자 이메일: {}", auth.getName());

        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    /**
     * (EN) 이 부분에서 EN 만 추출하는 코드
     */
        Pattern pattern = Pattern.compile("\\((.*?)\\)");
        Matcher matcher = pattern.matcher(language);

        // 추출된 언어 코드를 저장할 변수
        String extractedCode = "";

        if (matcher.find()) {
            extractedCode = matcher.group(1).trim().toLowerCase();
        }

        /*
         추출된 언어 코드를 translateLanguage 필드에 저장
         */
        if (!extractedCode.isEmpty()) {
            user.updateTranslateLanguage(extractedCode);
        } else {
            // 괄호가 없을 경우, 전체 문자열을 소문자로 저장
            user.updateLanguage(language.toLowerCase().trim());
        }

        userRepository.save(user);
        log.info("사용자 언어 및 번역 언어 저장 완료: userId={}, language={}, translateLanguage={}",
                user.getId(), user.getLanguage(), user.getTranslateLanguage());
    }



}