package core.domain.mainpage.service;

import core.domain.mainpage.repository.KoreanNewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KoreanNewsService {
    private final KoreanNewsRepository koreanNewsRepository;

    @Transactional(readOnly = true)
    public List<KoreanNewsResponse> getTop3News(KNewsContentType type) {
        return koreanNewsRepository.findTop3ByTypeOrderByCreatedAtDesc(type)
                .stream()
                .map(content -> new KoreanNewsResponse(
                        content.getTitle(),
                        content.getHtmlContent(),
                        content.getType(),
                        content.getCreatedAt()))
                .collect(Collectors.toList());
    }

}
