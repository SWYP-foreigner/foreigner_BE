package core.domain.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ForbiddenWordService {

    @Value("classpath:forbidden_words.json")
    private Resource forbiddenWordsFile;

    private List<String> forbiddenWords;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<String> words = objectMapper.readValue(forbiddenWordsFile.getInputStream(), new TypeReference<List<String>>() {});

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
        for (String word : forbiddenWords) {
            if (text.toLowerCase().contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}