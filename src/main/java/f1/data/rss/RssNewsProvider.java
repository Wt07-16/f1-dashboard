package f1.data.rss;

import f1.domain.DataException;
import f1.domain.model.NewsArticle;
import f1.domain.port.NewsProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * RSS 新闻源实现:从一组 feed 拉取并解析(标准 DOM,零额外依赖),
 * 解析失败只跳过该条/该源 —— 新闻页不允许被一个坏 feed 拖垮。
 *
 * 可达性说明:实测 formula1.com 官方 / motorsport.com 可达;feed 列表可扩展。
 */
public final class RssNewsProvider implements NewsProvider {

    /** 一个订阅源:显示名 + URL */
    public record Feed(String name, String url) {
    }

    private static final List<Feed> FEEDS = List.of(
            new Feed("F1 官方", "https://www.formula1.com/en/latest/all.xml"),
            new Feed("Motorsport", "https://www.motorsport.com/rss/f1/news/"));

    /** 各源互不依赖:并行拉取,总耗时取最慢的那个源而非相加 */
    private static final ExecutorService FEED_POOL =
            Executors.newFixedThreadPool(FEEDS.size(), r -> {
                Thread t = new Thread(r, "f1-rss");
                t.setDaemon(true);
                return t;
            });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final DateTimeFormatter RFC822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public List<NewsArticle> latestNews(int limit) {
        record FeedResult(Feed feed, List<NewsArticle> articles, boolean failed) {
        }
        List<CompletableFuture<FeedResult>> futures = FEEDS.stream()
                .map(feed -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return new FeedResult(feed, parseFeed(feed), false);
                    } catch (Exception e) {
                        return new FeedResult(feed, List.of(), true);
                    }
                }, FEED_POOL))
                .toList();

        List<NewsArticle> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (CompletableFuture<FeedResult> future : futures) {
            FeedResult r = future.join();
            all.addAll(r.articles());
            if (r.failed()) {
                errors.add(r.feed().name());
            }
        }
        // 按发布时间倒序;无时间的(null,如 F1 官方源)排到最后 ——
        // 注意不能用 reversed(nullsLast(...)):反转会把 null 翻到最前
        all.sort(Comparator.comparing(
                        (NewsArticle a) -> a.publishedAt() == null ? Instant.MIN : a.publishedAt())
                .reversed());
        List<NewsArticle> top = all.size() > limit ? all.subList(0, limit) : all;
        if (top.isEmpty() && !errors.isEmpty()) {
            throw new DataException("新闻源暂时无法访问: " + String.join("、", errors) + "(请检查网络)");
        }
        return top;
    }

    private static List<NewsArticle> parseFeed(Feed feed) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "f1-dashboard/0.1 (JavaFX desktop)")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("[RssNewsProvider] " + feed.name() + " HTTP " + response.statusCode());
            return List.of();
        }
        return parseXml(feed.name(), response.body());
    }

    private static List<NewsArticle> parseXml(String source, String xml) {
        List<NewsArticle> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = text(item, "title");
                String link = text(item, "link");
                if (title == null || title.isBlank()) {
                    continue;
                }
                Instant published = parseDate(text(item, "pubDate"));
                out.add(new NewsArticle(title, link, published, source));
            }
        } catch (Exception e) {
            // 单源解析失败:跳过该源(留痕便于排查,不让坏源拖垮整个列表)
            System.err.println("[RssNewsProvider] 解析失败: " + source + " → " + e);
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        return Optional.ofNullable(nodes.item(0).getTextContent()).map(String::trim).orElse("");
    }

    private static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw.trim(), RFC822).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(raw.trim());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
