package core.domain.post.service;

import core.domain.post.entity.CrawledData;
import core.domain.post.repository.CrawledDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.htmlunit.BrowserVersion;
import org.htmlunit.ScriptException;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.javascript.JavaScriptErrorListener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


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

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Scheduled(cron = "0 0 7 * * *")
    @Transactional
    public void crawlMyDramaList() {
        log.info("Starting mydramalist.com crawling with HtmlUnit...");

        Logger.getLogger("org.htmlunit").setLevel(Level.OFF);
        Logger.getLogger("org.htmlunit.javascript").setLevel(Level.OFF);
        Logger.getLogger("org.htmlunit.css").setLevel(Level.OFF);

        try (final WebClient webClient = new WebClient(BrowserVersion.CHROME)) {

            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setPrintContentOnFailingStatusCode(false);
            webClient.getOptions().setTimeout(20000);

            webClient.setJavaScriptErrorListener(new JavaScriptErrorListener() {
                @Override
                public void scriptException(HtmlPage page, ScriptException scriptException) {}
                @Override
                public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {}
                @Override
                public void malformedScriptURL(HtmlPage page, String url, MalformedURLException malformedURLException) {}
                @Override
                public void loadScriptError(HtmlPage page, URL scriptUrl, Exception exception) {}
                @Override
                public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {}
            });

            webClient.setCssErrorHandler(new org.htmlunit.cssparser.parser.CSSErrorHandler() {
                @Override
                public void error(org.htmlunit.cssparser.parser.CSSParseException exception) {}
                @Override
                public void fatalError(org.htmlunit.cssparser.parser.CSSParseException exception) {}
                @Override
                public void warning(org.htmlunit.cssparser.parser.CSSParseException exception) {}
            });

            webClient.setIncorrectnessListener((message, origin) -> {});

            webClient.addRequestHeader("User-Agent", USER_AGENT);
            webClient.addRequestHeader("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            webClient.addRequestHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            webClient.addRequestHeader("Referer", "https://www.google.com/");

            log.debug("HtmlUnit: Connecting to List Page {}", LIST_URL);
            final HtmlPage listPage = webClient.getPage(LIST_URL);

            webClient.waitForBackgroundJavaScript(15000);

            log.info("HtmlUnit: List Page loaded.");
            Document listDoc = Jsoup.parse(listPage.asXml());

            Elements articles = listDoc.select(LIST_ARTICLE_SELECTOR);
            log.info("Found {} articles on mydramalist search page.", articles.size());

            if (articles.isEmpty()) {
                log.warn("⚠️ Articles empty. Server IP might be blocked by Cloudflare challenge.");
            }

            for (Element articleElement : articles) {

                Element linkElement = articleElement.selectFirst(LIST_LINK_SELECTOR);
                Element thumbElement = articleElement.selectFirst(LIST_THUMBNAIL_SELECTOR);

                if (linkElement == null || thumbElement == null) {
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

                try {
                    crawledDataRepository.save(crawledData);
                    log.info("Successfully crawled and saved: {}", originalUrl);
                } catch (DataIntegrityViolationException e) {
                    log.warn("Duplicate entry found for URL: {}. Skipping.", originalUrl);
                }

                Thread.sleep(3000);
            }

        } catch (Exception e) {
            log.error("Error occurred during crawling mydramalist.com", e);
        }
        log.info("Finished mydramalist.com crawling.");
    }
}
