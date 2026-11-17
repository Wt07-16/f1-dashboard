package f1.domain.model;

import java.time.Instant;

/**
 * 一条新闻。来源可以是任何订阅源(RSS/其他),UI 只认这个形态。
 */
public record NewsArticle(
        String title,
        String link,
        Instant publishedAt,
        String sourceName) { // 如 "F1 官方" / "Motorsport"
}
