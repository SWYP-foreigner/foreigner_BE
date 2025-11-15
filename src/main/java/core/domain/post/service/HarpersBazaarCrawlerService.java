package core.domain.post.service;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class HarpersBazaarCrawlerService {

    private final CrawledDataRepository crawledDataRepository;
    private final TranslationService translationService;

    private static final String BASE_URL = "https://www.harpersbazaar.co.kr";
    private static final String AJAX_URL = BASE_URL + "/fashion/news/more";
    private static final String SOURCE_SITE = "harpersbazaar.co.kr";

    private static final String LIST_ARTICLE_SELECTOR = "li";
    private static final String LIST_LINK_SELECTOR = "a";
    private static final String LIST_THUMBNAIL_SELECTOR = "img";
    private static final String LIST_TITLE_SELECTOR = "p.tit";

    private static final String DETAIL_CONTENT_SELECTOR = "div.atc_body_cont > div:not(.atc_mask_login)";
    private static final String DETAIL_IMAGE_SELECTOR = "div.atc_body_cont img";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void crawlHarpersBazaar() {
        log.info("Starting harpersbazaar.co.kr crawling (AJAX)...");
        try {

            // 1단계: Jsoup.post()로 AJAX 요청
            Document listDoc = Jsoup.connect(AJAX_URL)
                    .userAgent(USER_AGENT)
                    .header("Referer", BASE_URL + "/fashion/news")
                    .header("Content-Type", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .requestBody("{\"offset\":0,\"limit\":24}")
                    .ignoreContentType(true)
                    .post();

            Elements articles = listDoc.select(LIST_ARTICLE_SELECTOR);
            log.info("Found {} articles from HarpersBazaar AJAX response.", articles.size());

            if (articles.isEmpty()) {
                log.warn("No articles found. AJAX request might have failed.");
                return;
            }

            Set<String> imageUrlSet = new HashSet<>();

            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
                Element thumbElement = articleElement.selectFirst(LIST_THUMBNAIL_SELECTOR);
                Element titleElement = articleElement.selectFirst(LIST_TITLE_SELECTOR);

                if (linkElement == null || thumbElement == null || titleElement == null) {
                    log.warn("Skipping article, essential element (link, thumb, or title) not found.");
                    continue;
                }

                String originalUrl = linkElement.absUrl("href");
                String title = titleElement.text();

                imageUrlSet.clear();
                imageUrlSet.add(thumbElement.absUrl("data-src"));

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String koreanContent = "";
                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl)
                            .userAgent(USER_AGENT)
                            .referrer(BASE_URL + "/fashion/news")
                            .get();

                    Element contentWrapper = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);

                    if (contentWrapper != null) {
                        contentWrapper.select("br").after("\n");
                        contentWrapper.select("div.ab_related_article").remove();

                        koreanContent = contentWrapper.text().replace("&nbsp;", " ");

                        Elements contentImages = contentWrapper.select("img");
                        contentImages.forEach(img -> imageUrlSet.add(img.absUrl("src")));
                    }

                    if(koreanContent.isEmpty()) {
                        koreanContent = title;
                        log.warn("Could not find detail content selector [{}], using title instead.", DETAIL_CONTENT_SELECTOR);
                    }
                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}. Skipping.", originalUrl, e);
                    continue;
                }

                String translatedContent = translationService.translatePost(koreanContent, "en");

                List<String> imageUrls = new ArrayList<>(imageUrlSet);
                CrawledData crawledData = new CrawledData(
                        title,
                        translatedContent,
                        originalUrl,
                        SOURCE_SITE,
                        imageUrls
                );
                crawledDataRepository.save(crawledData);
                log.info("Successfully crawled and saved: {}", originalUrl);

                Thread.sleep(3000);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling harpersbazaar.co.kr", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("HarpersBazaar 크롤링 실패: " + e.getMessage(), e);
        }
        log.info("Finished harpersbazaar.co.kr crawling.");
    }
}
