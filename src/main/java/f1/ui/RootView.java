package f1.ui;

import f1.app.F1Session;
import f1.ui.calendar.CalendarPage;
import f1.ui.charts.LapChartsPage;
import f1.ui.live.LivePage;
import f1.ui.news.NewsPage;
import f1.ui.notify.Notifier;
import f1.ui.settings.SettingsPage;
import f1.ui.standings.StandingsPage;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用主框架:左侧导航 + 中央页面容器。
 * 页面切换时通知旧页面 onHidden、新页面 onShown(生命周期,
 * 用于进入页面刷新数据 / 离开时停动画);页面互相不知道对方存在。
 */
public final class RootView extends BorderPane {

    private static final class Item {
        final String glyph;
        final String title;
        final F1Page page;
        Node node;

        Item(String glyph, String title, F1Page page) {
            this.glyph = glyph;
            this.title = title;
            this.page = page;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Button> navButtons = new ArrayList<>();
    private final VBox contentBox;
    private int current = -1;

    public RootView(F1Session session, Notifier notifier) {
        contentBox = new VBox();
        contentBox.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        contentBox.getStyleClass().add("page-content");
        setCenter(contentBox);

        // 页面在此组装(共享同一个 session 与通知器,互相不依赖)
        items.add(new Item("⚡", "实时排名", new LivePage(session, notifier)));
        items.add(new Item("🏆", "积分榜", new StandingsPage(session)));
        items.add(new Item("🗓️", "赛历", new CalendarPage(session)));
        items.add(new Item("📈", "圈速对比", new LapChartsPage(session)));
        items.add(new Item("📰", "新闻", new NewsPage(session)));
        items.add(new Item("⚙️", "设置", new SettingsPage(session, notifier)));

        setLeft(buildSidebar());
        select(0);
    }

    private VBox buildSidebar() {
        Label brand = new Label("F1 DASHBOARD");
        brand.getStyleClass().add("brand");

        VBox nav = new VBox(4);
        nav.setPadding(new Insets(12, 12, 12, 12));

        for (Item item : items) {
            Button btn = new Button(item.glyph + "  " + item.title);
            btn.getStyleClass().add("nav-btn");
            btn.setOnAction(e -> select(items.indexOf(item)));
            nav.getChildren().add(btn);
            navButtons.add(btn);
        }

        Label footer = new Label("免费公开数据源\n开发/离线: Mock 演示模式");
        footer.getStyleClass().add("sidebar-footer");
        VBox.setVgrow(nav, Priority.ALWAYS);

        VBox sidebar = new VBox(8, brand, nav, footer);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private void select(int index) {
        if (index == current) {
            return;
        }
        if (current >= 0) {
            items.get(current).page.onHidden();
        }
        current = index;
        for (int i = 0; i < navButtons.size(); i++) {
            navButtons.get(i).pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("current"), i == index);
        }
        Item item = items.get(index);
        if (item.node == null) {
            item.node = item.page.build();
        }
        contentBox.getChildren().setAll(item.node);
        VBox.setVgrow(item.node, Priority.ALWAYS);
        item.page.onShown();
    }
}
