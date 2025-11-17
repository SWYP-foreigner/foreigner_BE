package core.domain.post.service;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class MyDramaListCrawlerService {

    private final CrawledDataRepository crawledDataRepository;

    private static final String BASE_URL = "https://mydramalist.com";
    private static final String LIST_URL = BASE_URL + "/search?adv=titles&ty=68,77,86&co=3&so=newest&or=desc";
    private static final String SOURCE_SITE = "mydramalist.com";

    private static final String LIST_ARTICLE_SELECTOR = "div.box";
    private static final String LIST_LINK_SELECTOR = "h6.title a";
    private static final String LIST_THUMBNAIL_SELECTOR = "img.cover.lazy";

    private static final String DETAIL_CONTENT_SELECTOR = "div.show-synopsis > p > span";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    @Scheduled(cron = "0 0 7 * * *")
    @Transactional
    public void crawlMyDramaList() {
        log.info("Starting mydramalist.com crawling with HtmlUnit (2-Step)...");

        try (final WebClient webClient = new WebClient()) {
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);
            webClient.getOptions().setTimeout(15000);
            webClient.addRequestHeader("User-Agent", USER_AGENT);
            webClient.addRequestHeader("Accept-Language", "ko-KR,ko;q=0.9");
            webClient.addRequestHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");

            log.debug("HtmlUnit: Connecting to List Page {}", LIST_URL);
            final HtmlPage listPage = webClient.getPage(LIST_URL);
            webClient.waitForBackgroundJavaScript(10000);
            log.info("HtmlUnit: List Page loaded.");
            Document listDoc = Jsoup.parse(listPage.asXml());

            Elements articles = listDoc.select(LIST_ARTICLE_SELECTOR);
            log.info("Found {} articles on mydramalist search page.", articles.size());

            for (Element articleElement : articles) {

                Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
                Element thumbElement = articleElement.selectFirst(LIST_THUMBNAIL_SELECTOR);

                if (linkElement == null || thumbElement == null) {
                    log.warn("Skipping article, essential element (link or thumb) not found.");
                    continue;
                }

                String relativeUrl = linkElement.attr("href");
                String originalUrl = BASE_URL + relativeUrl;
                String title = linkElement.text();
                String imageUrl = thumbElement.absUrl("data-src");

                if (crawledDataRepository.existsByOriginalUrl(originalUrl)) {
                    log.debug("Skipping already crawled article: {}", originalUrl);
                    continue;
                }

                String fullContent = "";
                try {
                    log.info("Crawling detail page: {}", originalUrl);
                    final HtmlPage detailPage = webClient.getPage(originalUrl);
                    webClient.waitForBackgroundJavaScript(5000);

                    Document detailDoc = Jsoup.parse(detailPage.asXml());

                    Element contentElement = detailDoc.selectFirst(DETAIL_CONTENT_SELECTOR);

                    if (contentElement != null) {
                        fullContent = contentElement.text();
                    } else {
                        Element snippetElement = articleElement.selectFirst("div.content p:not(:has(span.rating))");
                        fullContent = (snippetElement != null) ? snippetElement.text() : title;
                        log.warn("Could not find full content for [{}], using snippet or title.", title);
                    }

                } catch (Exception e) {
                    log.error("Failed to crawl detail page: {}. Skipping article.", originalUrl, e);
                    continue;
                }

                CrawledData crawledData = new CrawledData(
                        title,
                        fullContent,
                        originalUrl,
                        SOURCE_SITE,
                        List.of(imageUrl)
                );
                crawledDataRepository.save(crawledData);
                log.info("Successfully crawled and saved: {}", originalUrl);

                Thread.sleep(3000);

            }

        } catch (Exception e) {
            log.error("Error occurred during crawling mydramalist.com", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("MyDramaList 크롤링 실패: " + e.getMessage(), e);
        }
        log.info("Finished mydramalist.com crawling.");
    }
}
