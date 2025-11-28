package core.domain.post.service;

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
public class AllkpopCrawlerService {

    private final CrawledDataRepository crawledDataRepository;

    private static final String BASE_URL = "https://www.allkpop.com";
    private static final String LIST_URL = BASE_URL + "/category/news";
    private static final String SOURCE_SITE = "allkpop.com";

    private static final String ARTICLE_SELECTOR = "div#more_stories_scr article.list";
    private static final String LINK_SELECTOR = "div.text div.title a";
    private static final String THUMBNAIL_SELECTOR = "div.image img.b-lazy";
    private static final String DETAIL_CONTENT_SELECTOR = "div.entry_content";

    @Scheduled(cron = "0 30 6 * * *")
    public void crawlAllkpopNews() {
        log.info("Starting allkpop.com news crawling...");
        try {
            Document listDoc = Jsoup.connect(LIST_URL).timeout(10000).get();
            Elements articles = listDoc.select(ARTICLE_SELECTOR);
            log.info("Found {} articles on allkpop list page.", articles.size());

            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst(LINK_SELECTOR);
                if (linkElement == null) continue;
                String relativeUrl = linkElement.attr("href");
                String originalUrl = BASE_URL + relativeUrl;
                String title = linkElement.text();

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                Set<String> imageUrlSet = new HashSet<>();

                Element thumbnailElement = articleElement.selectFirst(THUMBNAIL_SELECTOR);
                if (thumbnailElement != null) {
                    String rawUrl = thumbnailElement.absUrl("data-src");
                    if (rawUrl.contains("?")) {
                        rawUrl = rawUrl.substring(0, rawUrl.indexOf("?"));
                    }
                    rawUrl = rawUrl.replace("/thumb", "");

                    imageUrlSet.add(rawUrl);
                }

                String fullContent = "";

                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();

                    Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);

                    if (contentElement != null) {
                        Elements paragraphs = contentElement.select("p");

                        StringBuilder contentBuilder = new StringBuilder();
                        for (Element p : paragraphs) {
                            String text = p.text();

                            if (!text.startsWith("SEE ALSO:") && !text.isBlank()) {
                                contentBuilder.append(text).append("\n\n");
                            }
                        }
                        fullContent = contentBuilder.toString().trim();

                        Elements contentImages = contentElement.select("figure > img");
                        contentImages.forEach(img -> {
                            String rawUrl = img.absUrl("src");
                            if (rawUrl.contains("?")) {
                                rawUrl = rawUrl.substring(0, rawUrl.indexOf("?"));
                            }
                            rawUrl = rawUrl.replace("/thumb", "");
                            imageUrlSet.add(rawUrl);
                        });
                    }

                    if (fullContent.isEmpty()) {
                        fullContent = title;
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

                saveCrawledData(crawledData);

                Thread.sleep(3000);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling allkpop.com", e);
            Thread.currentThread().interrupt();
        }
        log.info("Finished allkpop.com community crawling.");
    }

    @Transactional
    public void saveCrawledData(CrawledData data) {
        try {
            crawledDataRepository.save(data);
            log.info("Successfully crawled and saved: {}", data.getOriginalUrl());
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate entry found. Skipping.");
        }
    }
}
