package core.domain.post.service;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoompiCrawlerService {

    private final CrawledDataRepository crawledDataRepository;

    private static final String RSS_FEED_URL = "https://www.soompi.com/feed";
    private static final String SOURCE_SITE = "soompi.com";

    private static final String RSS_ITEM_SELECTOR = "item";
    private static final String RSS_LINK_SELECTOR = "link";
    private static final String RSS_TITLE_SELECTOR = "title";

    private static final String DETAIL_CONTENT_WRAPPER = "div.article-wrapper > div";
    private static final String DETAIL_MAIN_IMAGE_SELECTOR = "span.image-wrapper img";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    @Scheduled(cron = "0 45 6 * * *")
    @Transactional
    public void crawlSoompiLatest() {
        log.info("Starting soompi.com latest news crawling (RSS Feed)...");
        try {

            Document feedDoc = Jsoup.connect(RSS_FEED_URL)
                    .userAgent(USER_AGENT)
                    .parser(Parser.xmlParser())
                    .get();

            Elements articles = feedDoc.select(RSS_ITEM_SELECTOR);
            log.info("Found {} articles from Soompi RSS feed.", articles.size());

            if (articles.isEmpty()) {
                log.warn("No articles found in RSS feed.");
                return;
            }

            Set<String> imageUrlSet = new HashSet<>();

            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst(RSS_LINK_SELECTOR);
                Element titleElement = articleElement.selectFirst(RSS_TITLE_SELECTOR);

                if (linkElement == null || titleElement == null) {
                    continue;
                }

                String originalUrl = linkElement.text();
                String title = titleElement.text();

                imageUrlSet.clear();

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String fullContent = "";
                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl)
                            .userAgent(USER_AGENT)
                            .referrer("https://www.soompi.com/latest")
                            .get();

                    Element thumbElement = detailDoc.selectFirst(DETAIL_MAIN_IMAGE_SELECTOR);
                    if (thumbElement != null) {
                        imageUrlSet.add(thumbElement.absUrl("src"));
                    }

                    Element contentWrapper = detailDoc.selectFirst(DETAIL_CONTENT_WRAPPER);

                    if (contentWrapper != null) {
                        StringBuilder contentBuilder = new StringBuilder();
                        for (Element p : contentWrapper.select("p")) {
                            String text = p.text();
                            if (!text.startsWith("Source (") &&
                                    !text.startsWith("In the meantime, watch") &&
                                    !p.hasClass("has-text-align-center")) {
                                contentBuilder.append(text).append("\n\n");
                            }
                        }
                        fullContent = contentBuilder.toString().trim();

                        Elements contentImages = contentWrapper.select("figure.wp-block-image img");
                        contentImages.forEach(img -> imageUrlSet.add(img.absUrl("src")));
                    }

                    if(fullContent.isEmpty()) {
                        fullContent = title;
                    }
                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}. Skipping.", originalUrl, e);
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
            log.error("Error occurred during crawling soompi.com RSS", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Finished soompi.com crawling.");
    }
}