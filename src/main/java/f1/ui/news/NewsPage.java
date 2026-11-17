package f1.ui.news;

import f1.app.F1Session;
import f1.domain.model.NewsArticle;
import f1.ui.F1Page;
import f1.ui.util.Async;
import f1.ui.util.StateCard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 新闻页:多源聚合列表(F1 官方 / Motorsport),点标题用系统浏览器打开原文。
 * 拉取/解析在后台线程,页面只订阅端口结果。
 */
public final class NewsPage implements F1Page {

    private static final int NEWS_LIMIT = 25;

    private final F1Session session;
    private final VBox root = new VBox(10);
    private final Label statusLabel = new Label();
    private final VBox listBox = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(listBox);

    private final DateTimeFormatter dateText =
            DateTimeFormatter.ofPattern("M月d日 HH:mm", java.util.Locale.CHINA);

    public NewsPage(F1Session session) {
        this.session = session;
        buildUi();
    }

    private void buildUi() {
        root.setPadding(new Insets(18, 22, 18, 22));

        HBox head = new HBox(10);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox titles = new VBox(2);
        Label title = new Label("新闻");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("📰 F1 官方 / Motorsport 聚合 · 点击标题在浏览器打开原文");
        subtitle.getStyleClass().add("page-subtitle");
        titles.getChildren().addAll(title, subtitle);

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("↻ 刷新");
        refresh.getStyleClass().add("ghost");
        refresh.setOnAction(e -> reload(true)); // 手动刷新绕过缓存

        statusLabel.getStyleClass().add("loading-text");
        head.getChildren().addAll(titles, spacer, refresh, statusLabel);
        root.getChildren().add(head);

        listBox.setMaxWidth(Double.MAX_VALUE);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
    }

    private void reload(boolean force) {
        setStatus("⏳ 加载新闻中…", false);
        listBox.getChildren().clear();
        Async.run(session.executor(), () -> session.newsCached(NEWS_LIMIT, force),
                this::onLoaded, this::onError);
    }

    private void onLoaded(List<NewsArticle> articles) {
        setStatus("✓ " + articles.size() + " 条", false);
        listBox.getChildren().clear();
        if (articles.isEmpty()) {
            Label empty = new Label("暂无新闻");
            empty.getStyleClass().add("muted");
            listBox.getChildren().add(empty);
            return;
        }
        for (NewsArticle article : articles) {
            listBox.getChildren().add(buildCard(article));
        }
    }

    private Node buildCard(NewsArticle article) {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label(article.title());
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #e8e8f0;"
                + "-fx-cursor: hand; -fx-wrap-text: true;");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setOnMouseClicked(e -> openInBrowser(article.link()));

        HBox meta = new HBox(10);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label source = new Label(article.sourceName());
        source.getStyleClass().add("chip");
        Label time = new Label(relativeTime(article.publishedAt()));
        time.getStyleClass().add("muted");
        time.setStyle("-fx-font-size: 12px;");
        meta.getChildren().addAll(source, time);
        card.getChildren().addAll(title, meta);
        return card;
    }

    private void openInBrowser(String link) {
        if (link == null || link.isBlank()) {
            return;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                setStatus("⚠ 当前环境不支持打开浏览器", true);
                return;
            }
            Desktop.getDesktop().browse(URI.create(link));
        } catch (IOException | IllegalArgumentException e) {
            setStatus("⚠ 打开链接失败: " + link, true);
        }
    }

    private String relativeTime(Instant instant) {
        if (instant == null) {
            return "";
        }
        Duration ago = Duration.between(instant, Instant.now());
        if (ago.isNegative() || ago.toMinutes() < 1) {
            return "刚刚";
        }
        if (ago.toHours() < 1) {
            return ago.toMinutes() + " 分钟前";
        }
        if (ago.toDays() < 1) {
            return ago.toHours() + " 小时前";
        }
        if (ago.toDays() < 7) {
            return ago.toDays() + " 天前";
        }
        return dateText.format(instant.atZone(ZoneId.systemDefault()));
    }

    private void onError(Throwable t) {
        setStatus("⚠ " + t.getMessage(), true);
        // 内容区不再全空白:展示原因卡 + 重试入口
        listBox.getChildren().setAll(StateCard.error(t.getMessage() + " —— 新闻需要联网",
                () -> reload(true)));
    }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("loading-text", "error-text");
        statusLabel.getStyleClass().add(error ? "error-text" : "loading-text");
    }

    @Override
    public Node build() {
        return root;
    }

    @Override
    public void onShown() {
        reload(false); // 60 秒内直接命中缓存,瞬时显示
    }
}
