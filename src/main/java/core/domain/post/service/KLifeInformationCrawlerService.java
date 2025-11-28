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
public class KLifeInformationCrawlerService {

    private final CrawledDataRepository crawledDataRepository;
    private final TranslationService translationService;

    private static final String BASE_URL = "https://k-life.co";
    private static final String LIST_URL = BASE_URL + "/information";
    private static final String SOURCE_SITE = "k-life.co";

    private static final String ARTICLE_SELECTOR = "li.article-section";
    private static final String LINK_SELECTOR = "a.uk-link-reset:has(p.uk-text-break)";
    private static final String TITLE_SELECTOR = "h3.uk-card-title strong";
    private static final String SNIPPET_SELECTOR = "p.uk-text-break";
    private static final String THUMBNAIL_SELECTOR = "img[uk-cover]";

    private static final String DETAIL_CONTENT_SELECTOR = "div.rhymix_content.xe_content";
    private static final String DETAIL_IMAGE_SELECTOR = "div.rhymix_content.xe_content img";

    @Scheduled(cron = "0 35 5 * * *")
    public void crawlKLifeInformation() {
        log.info("Starting k-life.co /information crawling...");
        try {
            Document listDoc = Jsoup.connect(LIST_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
                    .timeout(10000).get();

            Elements articles = listDoc.select(ARTICLE_SELECTOR);
            log.info("Found {} articles on k-life /information page.", articles.size());

            for (Element articleElement : articles) {

                Element linkElement = articleElement.selectFirst(LINK_SELECTOR);
                Element titleElement = articleElement.selectFirst(TITLE_SELECTOR);

                if (linkElement == null || titleElement == null) {
                    log.warn("Skipping article, link or title not found.");
                    continue;
                }

                Element snippetElement = linkElement.selectFirst(SNIPPET_SELECTOR);
                Element thumbElement = linkElement.selectFirst(THUMBNAIL_SELECTOR);

                String relativeUrl = linkElement.attr("href");
                String originalUrl = BASE_URL + relativeUrl;
                String titleKOR = titleElement.text();
                String descriptionKOR = (snippetElement != null) ? snippetElement.text() : "";

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String fullContentKOR = "";
                Set<String> imageUrlSet = new HashSet<>();

                if (thumbElement != null) {
                    String thumbUrl = thumbElement.absUrl("src");
                    if (thumbUrl != null && !thumbUrl.contains("no-image.png")) {
                        imageUrlSet.add(getHighQualityUrl(thumbUrl));
                    }
                }

                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
                            .timeout(10000).get();

                    Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);

                    if (contentElement != null) {
                        fullContentKOR = contentElement.text();
                        Elements contentImages = contentElement.select(DETAIL_IMAGE_SELECTOR);

                        contentImages.forEach(img -> {
                            String imgUrl = img.absUrl("src");
                            if (imgUrl != null && !imgUrl.isEmpty()) {
                                imageUrlSet.add(getHighQualityUrl(imgUrl));
                            }
                        });
                    } else {
                        fullContentKOR = descriptionKOR;
                    }

                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}. Skipping.", originalUrl, e);
                    continue;
                }

                String translatedTitle = translationService.translatePost(titleKOR, "en");
                String translatedContent = translationService.translatePost(fullContentKOR, "en");

                List<String> imageUrls = new ArrayList<>(imageUrlSet);

                CrawledData crawledData = new CrawledData(
                        translatedTitle,
                        translatedContent,
                        originalUrl,
                        SOURCE_SITE,
                        imageUrls
                );

                saveCrawledData(crawledData);

                Thread.sleep(3000);

            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling k-life.co /information", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Finished k-life.co /information crawling.");
    }

    private String getHighQualityUrl(String url) {
        if (url == null) return "";
        if (url.contains("?")) {
            return url.substring(0, url.indexOf("?"));
        }
        return url;
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
