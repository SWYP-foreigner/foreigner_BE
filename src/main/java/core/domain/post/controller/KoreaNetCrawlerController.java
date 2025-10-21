package core.domain.post.controller;

import core.domain.post.service.KoreaNetCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/crawl")
@RequiredArgsConstructor
public class KoreaNetCrawlerController {

    private final KoreaNetCrawlerService crawlerService;

    @GetMapping("/koreanet/festivals")
    public ResponseEntity<String> triggerKoreaNetFestivalCrawl() {
        crawlerService.crawlKoreaNetFestivals();
        return ResponseEntity.ok("Korea.net Festival crawling triggered successfully.");
    }
}
