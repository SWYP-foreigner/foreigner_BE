package core.domain.post.controller;

import core.domain.post.service.*;
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
    private final SeoulGlobalCrawlerService seoulGlobalCrawlerService;
    private final AllkpopCrawlerService allkpopCrawlerService;
    private final MyDramaListCrawlerService myDramaListCrawlerService;
    private final SoompiCrawlerService soompiCrawlerService;

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

    /**
     * Seoul Global Center 소식 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/seoulglobal/news")
    public ResponseEntity<String> triggerSeoulGlobalNewsCrawl() {
        seoulGlobalCrawlerService.crawlSeoulGlobalNews();
        return ResponseEntity.ok("Seoul Global Center news crawling triggered successfully.");
    }

    /**
     * allkpop.com 뉴스 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/allkpop/news")
    public ResponseEntity<String> triggerAllkpopNewsCrawl() {
        allkpopCrawlerService.crawlAllkpopNews();
        return ResponseEntity.ok("Allkpop news crawling triggered successfully.");
    }

    /**
     * mydramalist.com 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/mydramalist")
    public ResponseEntity<String> triggerMyDramaListCrawl() {
        myDramaListCrawlerService.crawlMyDramaList();
        return ResponseEntity.ok("MyDramaList crawling triggered successfully.");
    }

    /**
     * soompi.com 최신 뉴스 크롤링을 수동으로 실행합니다.
     */
    @GetMapping("/soompi/latest")
    public ResponseEntity<String> triggerSoompiCrawl() {
        soompiCrawlerService.crawlSoompiLatest();
        return ResponseEntity.ok("Soompi latest news crawling triggered successfully.");
    }
}
