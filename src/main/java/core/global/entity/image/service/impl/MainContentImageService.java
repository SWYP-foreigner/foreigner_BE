package core.global.entity.image.service.impl;

import core.global.enums.PollType;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

public interface MainContentImageService {

    @Async("imageExecutor")
    @Transactional
    void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImages, PollType pollType);

}
