package core.domain.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ForbiddenWordService {

    @Value("classpath:forbidden_words.json")
    private Resource forbiddenWordsFile;

    private List<String> forbiddenWords;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<String> words = objectMapper.readValue(forbiddenWordsFile.getInputStream(), new TypeReference<List<String>>() {
            });

            this.forbiddenWords = words;
            System.out.println("금칙어 " + this.forbiddenWords.size() + "개를 로드했습니다.");

        } catch (IOException e) {
            System.err.println("금칙어 파일을 로드하는 데 실패했습니다: " + e.getMessage());
            this.forbiddenWords = Collections.emptyList();
        }
    }

    public boolean containsForbiddenWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        final java.util.Locale L = java.util.Locale.ROOT;
        final String lowerText = text.toLowerCase(L);
        final int ctx = 15; // 컨텍스트로 함께 보여줄 앞/뒤 글자 수

        for (String word : forbiddenWords) {
            if (word == null || word.isBlank()) continue;

            String wLower = word.toLowerCase(L);
            int from = 0;
            boolean hitThisWord = false;

            // 같은 금칙어가 여러 번 등장할 수 있으니 모두 로그
            while (true) {
                int pos = lowerText.indexOf(wLower, from);
                if (pos < 0) break;

                int end = pos + wLower.length();

                // 컨텍스트 추출
                int a = Math.max(0, pos - ctx);
                int b = Math.min(text.length(), end + ctx);
                String context = text.substring(a, b);

                // 상세 로그
                log.info("forbidden hit: word='{}', range=[{}-{}), context='{}'",
                        word, pos, end, context);

                hitThisWord = true;
                from = end; // 다음 발생 위치 탐색
            }

            if (hitThisWord) {
                // 원래 로직 유지: 첫 매칭 시 true 반환
                return true;
            }
        }
        return false;
    }

}