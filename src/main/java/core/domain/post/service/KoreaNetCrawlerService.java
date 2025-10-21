package core.domain.post.service;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoreaNetCrawlerService {

    private final CrawledDataRepository crawledDataRepository;
    private static final String BASE_URL = "https://www.korea.net";
    private static final String LIST_URL = BASE_URL + "/Events/Festivals";
    private static final String SOURCE_SITE = "korea.net";
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("contentView\\(\\s*'[^']+',\\s*'(\\d+)',");

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void crawlKoreaNetFestivals() {
        log.info("Starting Korea.net festival crawling from HTML list...");
        try {

            Document listDoc = Jsoup.connect(LIST_URL).timeout(10000).get();
            Elements articles = listDoc.select("div.thumb-list div.list-box");
            log.info("Found {} articles on the list page.", articles.size());


            for (Element articleElement : articles) {
                Element linkElement = articleElement.selectFirst("div.txt-wrap > a");
                if (linkElement == null) continue;

                String contentViewCall = linkElement.attr("href");
                Matcher matcher = ARTICLE_ID_PATTERN.matcher(contentViewCall);
                if (!matcher.find()) continue;

                String contentId = matcher.group(1);
                String originalUrl = BASE_URL + "/Events/Festivals/view?articleId=" + contentId;

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String title = articleElement.selectFirst("p.tit").text();
                String description = articleElement.selectFirst("p.txt").text();

                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    Document detailDoc = Jsoup.connect(originalUrl).timeout(10000).get();

                    Element contentDiv = detailDoc.selectFirst("div.post-txt");
                    String fullContent = (contentDiv != null) ? contentDiv.text() : description;

                    Set<String> imageUrlSet = new HashSet<>();
                    Element mainImage = detailDoc.selectFirst("div.post-img img");
                    if (mainImage != null) imageUrlSet.add(mainImage.absUrl("src"));
                    Elements contentImages = detailDoc.select("div.post-txt img");
                    contentImages.forEach(img -> imageUrlSet.add(img.absUrl("src")));
                    List<String> imageUrls = new ArrayList<>(imageUrlSet);

                    CrawledData crawledData = new CrawledData(title, fullContent, originalUrl, SOURCE_SITE, imageUrls);
                    crawledDataRepository.save(crawledData);
                    log.info("Successfully crawled and saved: {}", originalUrl);

                } catch (IOException e) {
                    log.error("Failed to crawl detail page: {}", originalUrl, e);
                    continue;
                }

                Thread.sleep(2500);

            }

        } catch (IOException | InterruptedException e) {
            log.error("Error occurred during crawling Korea.net list HTML", e);
            Thread.currentThread().interrupt();
        }
        log.info("Finished Korea.net festival crawling from HTML list.");
    }
}
