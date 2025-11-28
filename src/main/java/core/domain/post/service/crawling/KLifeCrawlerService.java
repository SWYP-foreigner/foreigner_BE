package core.domain.post.service.crawling;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.dao.DataIntegrityViolationException;
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
public class KLifeCrawlerService {

    private final CrawledDataRepository crawledDataRepository;
    private static final String BASE_URL = "https://k-life.co";
    private static final String LIST_URL = BASE_URL + "/community";
    private static final String SOURCE_SITE = "k-life.co";
    private static final String ARTICLE_SELECTOR = "div.article-section";
    private static final String LINK_SELECTOR = "a.uk-link-toggle";
    private static final String TITLE_SELECTOR = "h3.document-list-title strong";
    private static final String SNIPPET_SELECTOR = "p > span.uk-text-break";
    private static final String THUMBNAIL_SELECTOR = "div.files-area img";
    private static final String DETAIL_CONTENT_SELECTOR = "div.rhymix_content.xe_content";
    private static final String DETAIL_IMAGE_SELECTOR = "div.rhymix_content.xe_content img";

    @Scheduled(cron = "0 30 5 * * *")
    @Transactional
    public void crawlKLifeCommunity() {
        log.info("Starting k-life.co community crawling...");
        try {
            Document listDoc = Jsoup.connect(LIST_URL).timeout(10000).get();
            Elements articles = listDoc.select(ARTICLE_SELECTOR);
            log.info("Found {} articles on k-life list page.", articles.size());

            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst(LINK_SELECTOR);
                if (linkElement == null) {
                    log.warn("Skipping article, link not found.");
                    continue;
                }

                String relativeUrl = linkElement.attr("href");
                String originalUrl = BASE_URL + relativeUrl;
                String title = linkElement.selectFirst(TITLE_SELECTOR).text();
                String description = articleElement.selectFirst(SNIPPET_SELECTOR).text();

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String fullContent = "";
                Set<String> imageUrlSet = new HashSet<>();

                Element thumbElement = articleElement.selectFirst(THUMBNAIL_SELECTOR);
                if (thumbElement != null) {
                    String thumbUrl = thumbElement.absUrl("src");
                    if (!thumbUrl.contains("no-image.png")) {
                        imageUrlSet.add(thumbUrl);
                    }
                }

                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();

                    Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);
                    fullContent = (contentElement != null) ? contentElement.text() : description;

                    if (contentElement != null) {
                        Elements contentImages = contentElement.select(DETAIL_IMAGE_SELECTOR);
                        contentImages.forEach(img -> imageUrlSet.add(img.absUrl("src")));
                    }

                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}", originalUrl, e);
                    continue;
                }

                List<String> imageUrls = new ArrayList<>(imageUrlSet);

                CrawledData crawledData = new CrawledData(
                        title,
                        fullContent,
                        originalUrl,
                        SOURCE_SITE,
                        imageUrls
                );
                try {
                    crawledDataRepository.save(crawledData);
                    log.info("Successfully crawled and saved: {}", originalUrl);
                } catch (DataIntegrityViolationException e) {
                    log.warn("Duplicate entry found for URL: {}. Skipping.", originalUrl);
                }

                Thread.sleep(3000);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling k-life.co", e);
            Thread.currentThread().interrupt();
        }
        log.info("Finished k-life.co community crawling.");
    }
}
