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

    @Transactional
    public void saveUserLanguage(Authentication auth, String language) {
        log.info("[saveUserLanguage] 시작 - authName={}, language={}", auth.getName(), language);

        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        log.info("[saveUserLanguage] 사용자 조회 완료 - userId={}, email={}", user.getId(), user.getEmail());

        // (EN) 에서 EN 만 추출
        Pattern pattern = Pattern.compile("\\((.*?)\\)");
        Matcher matcher = pattern.matcher(language);

        String extractedCode = "";

        if (matcher.find()) {
            extractedCode = matcher.group(1).trim().toLowerCase();
            log.info("[saveUserLanguage] 괄호 안 언어 코드 추출 - extractedCode={}", extractedCode);
        } else {
            log.info("[saveUserLanguage] 괄호 없음 - 원문 그대로 사용 예정");
        }

        // 언어 업데이트
        if (!extractedCode.isEmpty()) {
            user.updateTranslateLanguage(extractedCode);
            log.info("[saveUserLanguage] translateLanguage 업데이트 - translateLanguage={}", extractedCode);
        } else {
            user.updateLanguage(language.toLowerCase().trim());
            log.info("[saveUserLanguage] language 업데이트 - language={}", language.toLowerCase().trim());
        }

        userRepository.save(user);
        log.info("[saveUserLanguage] 저장 완료 - userId={}, language={}, translateLanguage={}",
                user.getId(), user.getLanguage(), user.getTranslateLanguage());
    }




}