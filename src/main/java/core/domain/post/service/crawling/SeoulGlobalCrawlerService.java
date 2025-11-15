package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeoulGlobalCrawlerService {

    private final CrawledDataRepository crawledDataRepository;
    private final TranslationService translationService;

    private static final String BASE_URL = "https://global.seoul.go.kr";
    private static final String LIST_API_URL = BASE_URL + "/web/news/senw/bordContListPgng.do";
    private static final String DETAIL_URL_PREFIX = BASE_URL + "/web/news/senw/bordContDetail.do?brd_no=5&lang=ko&post_no=";
    private static final String SOURCE_SITE = "global.seoul.go.kr";
    private static final Pattern POST_NO_PATTERN = Pattern.compile("contDetail\\('([^']+)'\\)");

    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void crawlSeoulGlobalNews() {
        log.info("Starting Seoul Global Center news crawling...");
        try {
            Document listDoc = Jsoup.connect(LIST_API_URL)
                    .method(Connection.Method.POST)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://global.seoul.go.kr/web/news/senw/bordContListPage.do?brd_no=5")
                    .data("miv_pageNo", "1")
                    .data("brd_no", "5")
                    .data("searchkey", "T")
                    .data("lang", "ko")
                    .data("sidx", "NOTI_YN DESC, REF_NO DESC, REF_STEP_NO")
                    .data("sord", "ASC")
                    .post();

            Elements articles = listDoc.select("tbody tr");
            log.info("Found {} articles on Seoul Global list page.", articles.size());

            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst("td.title_box a.title");
                if (linkElement == null) continue;

                String href = linkElement.attr("href");
                Matcher matcher = POST_NO_PATTERN.matcher(href);
                if (!matcher.find()) continue;

                String postNoGuid = matcher.group(1);
                String originalUrl = DETAIL_URL_PREFIX + postNoGuid;
                String title = linkElement.text();

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String fullContent = "";
                List<String> imageUrls = new ArrayList<>();

                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();

                    Element contentElement = detailDoc.selectFirst("div.content");

                    if (contentElement != null) {
                        fullContent = contentElement.text();

                        Set<String> imageUrlSet = new HashSet<>();
                        Elements contentImages = contentElement.select("img");

                        contentImages.forEach(img -> {
                            String imageUrl = img.absUrl("src");
                            if (imageUrl != null && !imageUrl.isBlank()) {
                                imageUrlSet.add(imageUrl);
                            }
                        });
                        imageUrls = new ArrayList<>(imageUrlSet);
                    } else {
                        fullContent = title;
                    }

                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}", originalUrl, e);
                    continue;
                }

                log.info("Translating content for: {}", title);
                String translatedContent = translationService.translatePost(fullContent, "en");

                CrawledData crawledData = new CrawledData(
                        title,
                        translatedContent,
                        originalUrl,
                        SOURCE_SITE,
                        imageUrls
                );

                crawledDataRepository.save(crawledData);
                log.info("Successfully crawled, translated, and saved: {}", originalUrl);

                Thread.sleep(3000);

            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling Seoul Global Center", e);
            Thread.currentThread().interrupt();
        }
        log.info("Finished Seoul Global Center crawling.");
    }
}
