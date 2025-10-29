package core.domain.post.controller;

import core.domain.post.service.KLifeCrawlerService;
import core.domain.post.service.KoreaNetCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/crawl")
@RequiredArgsConstructor
public class CrawlerController {

    private final KoreaNetCrawlerService koreaNetCrawlerService;
    private final KLifeCrawlerService kLifeCrawlerService;

    /**
     * Korea.net 축제 정보 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/koreanet/festivals")
    public ResponseEntity<String> triggerKoreaNetFestivalCrawl() {
        koreaNetCrawlerService.crawlKoreaNetFestivals();
        return ResponseEntity.ok("Korea.net Festival crawling triggered successfully.");
    }

    /**
     * k-life.co 커뮤니티 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/klife/community")
    public ResponseEntity<String> triggerKLifeCommunityCrawl() {
        kLifeCrawlerService.crawlKLifeCommunity();
        return ResponseEntity.ok("k-life.co community crawling triggered successfully.");
    }
}
