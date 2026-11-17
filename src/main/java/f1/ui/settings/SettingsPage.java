package f1.ui.settings;

import f1.app.AppConfig;
import f1.app.F1Session;
import f1.ui.F1Page;
import f1.ui.notify.Notifier;
import f1.ui.util.Async;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 设置页:数据源切换(真实 Jolpica / Mock 演示)与桌面通知开关。
 * 选择即时生效并持久化到 ~/.f1-dashboard/config.json(下次启动沿用);
 * 积分榜/赛历等页面持有的接口引用不变 —— 组合根内部换实现,UI 零改动。
 */
public final class SettingsPage implements F1Page {

    private final F1Session session;
    private final Notifier notifier;
    private final VBox root = new VBox(14);

    public SettingsPage(F1Session session, Notifier notifier) {
        this.session = session;
        this.notifier = notifier;
        buildUi();
    }

    private void buildUi() {
        root.setPadding(new Insets(20, 24, 20, 24));

        Label title = new Label("设置");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("⚙️ 数据源与通知 · 修改即时生效并保存");
        subtitle.getStyleClass().add("page-subtitle");
        root.getChildren().addAll(title, subtitle);

        // ---- 数据源 ----
        VBox sourceCard = card("📡 数据源");
        Label sourceHint = new Label("当前:"
                + (session.source() == AppConfig.Source.REAL
                        ? "真实数据(Jolpica)—— 积分榜/赛历联网拉取,新闻来自官方 RSS"
                        : "Mock 演示 —— 积分榜/赛历为内嵌快照(第 13 站赛后),完全离线可用"));
        sourceHint.getStyleClass().add("caption");
        sourceHint.setWrapText(true);

        ToggleButton realBtn = new ToggleButton("真实数据");
        realBtn.getStyleClass().add("seg");
        ToggleButton mockBtn = new ToggleButton("Mock 演示");
        mockBtn.getStyleClass().add("seg");
        ToggleGroup srcGroup = new ToggleGroup();
        realBtn.setToggleGroup(srcGroup);
        mockBtn.setToggleGroup(srcGroup);
        (session.source() == AppConfig.Source.REAL ? realBtn : mockBtn).setSelected(true);
        realBtn.setOnAction(e -> applySource(AppConfig.Source.REAL, sourceHint));
        mockBtn.setOnAction(e -> applySource(AppConfig.Source.MOCK, sourceHint));

        HBox segRow = new HBox(8, realBtn, mockBtn);
        segRow.setAlignment(Pos.CENTER_LEFT);
        sourceCard.getChildren().addAll(new HBox(10, segRow, new Label(
                "切换后积分榜等页面刷新即用新数据源;实时页三种模式不受影响")), sourceHint);
        root.getChildren().add(sourceCard);

        // ---- OpenF1 实时数据凭据 (Sponsor) ----
        VBox openf1Card = card("🔑 OpenF1 实时订阅凭据 (Sponsor)");
        Label openf1Desc = new Label(
                "用于解锁比赛直播期间的全部实时数据(一练/二练/三练/冲刺排位/冲刺赛/排位赛/正赛)。\n"
                + "方式一:填写 OpenF1 订阅账号(邮箱)与密码,应用将自动换取并续期令牌;\n"
                + "方式二:若已有现成 Access Token / API Key,直接填入第三项即可(优先使用)。");
        openf1Desc.getStyleClass().add("caption");
        openf1Desc.setWrapText(true);

        TextField userField = new TextField(session.config().openf1Username);
        userField.setPromptText("OpenF1 账号邮箱 (username)");
        userField.setPrefWidth(240);

        PasswordField passField = new PasswordField();
        passField.setText(session.config().openf1Password);
        passField.setPromptText("OpenF1 密码 (password)");
        passField.setPrefWidth(200);

        HBox userPassRow = new HBox(10, new Label("账号:"), userField, new Label("密码:"), passField);
        userPassRow.setAlignment(Pos.CENTER_LEFT);

        PasswordField tokenField = new PasswordField();
        tokenField.setText(session.config().openf1Token);
        tokenField.setPromptText("可选:直接粘贴 Bearer Token / API Key(填此项则无需账号密码)");
        HBox.setHgrow(tokenField, Priority.ALWAYS);
        HBox tokenRow = new HBox(10, new Label("直填令牌:"), tokenField);
        tokenRow.setAlignment(Pos.CENTER_LEFT);

        Label openf1Status = new Label(session.config().hasOpenF1Credentials()
                ? "状态:已保存 OpenF1 凭据(可点「测试连接」验证)"
                : "状态:未填写凭据(平时可免费查看历史场次;直播期间需 Sponsor 凭据)");
        openf1Status.getStyleClass().add("caption");
        openf1Status.setWrapText(true);

        Button saveCredBtn = new Button("💾 保存凭据");
        saveCredBtn.getStyleClass().add("primary");
        Button testConnBtn = new Button("🔌 测试连接");
        testConnBtn.getStyleClass().add("ghost");
        Button clearCredBtn = new Button("清空");
        clearCredBtn.getStyleClass().add("ghost");

        saveCredBtn.setOnAction(e -> {
            session.updateOpenF1Credentials(
                    userField.getText(), passField.getText(), tokenField.getText());
            openf1Status.getStyleClass().removeAll("error-text");
            openf1Status.setText(session.config().hasOpenF1Credentials()
                    ? "✓ 凭据已保存到 ~/.f1-dashboard/config.json"
                    : "✓ 已清空凭据(恢复为免费档)");
        });

        clearCredBtn.setOnAction(e -> {
            userField.clear();
            passField.clear();
            tokenField.clear();
            session.updateOpenF1Credentials("", "", "");
            openf1Status.getStyleClass().removeAll("error-text");
            openf1Status.setText("✓ 已清空凭据(当前为免费档模式)");
        });

        testConnBtn.setOnAction(e -> {
            session.updateOpenF1Credentials(
                    userField.getText(), passField.getText(), tokenField.getText());
            openf1Status.getStyleClass().removeAll("error-text");
            openf1Status.setText("⏳ 正在连接 OpenF1 验证凭据…");
            testConnBtn.setDisable(true);
            Async.run(session.executor(), session::testOpenF1Connection,
                    msg -> {
                        testConnBtn.setDisable(false);
                        openf1Status.getStyleClass().removeAll("error-text");
                        openf1Status.setText(msg);
                    },
                    err -> {
                        testConnBtn.setDisable(false);
                        openf1Status.getStyleClass().add("error-text");
                        openf1Status.setText("⚠️ " + err.getMessage());
                    });
        });

        HBox actionRow = new HBox(10, saveCredBtn, testConnBtn, clearCredBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        openf1Card.getChildren().addAll(openf1Desc, userPassRow, tokenRow, actionRow, openf1Status);
        root.getChildren().add(openf1Card);

        // ---- 通知 ----
        VBox notifyCard = card("🔔 桌面通知");
        CheckBox notifyBox = new CheckBox("弹通知:开赛提醒(距开赛 90 分钟内)+ 实时前三名次变动");
        notifyBox.setSelected(notifier != null && notifier.enabled());
        notifyBox.getStyleClass().add("notify-box");
        notifyBox.setDisable(notifier == null || !notifier.available());
        notifyBox.setOnAction(e -> {
            if (notifier == null) {
                return;
            }
            boolean on = notifyBox.isSelected();
            notifier.setEnabled(on);
            session.config().notifyEnabled = on;
            session.config().save();
        });
        Label notifyHint = new Label(notifier != null && notifier.available()
                ? "通知走系统横幅,托盘图标(菜单栏红白方格)右键可退出应用"
                : "当前系统不支持系统托盘,通知功能不可用");
        notifyHint.getStyleClass().add("caption");
        notifyHint.setWrapText(true);
        notifyCard.getChildren().addAll(notifyBox, notifyHint);
        root.getChildren().add(notifyCard);

        // ---- 底部占位弹性 ----
        Label spacer = new Label();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        root.getChildren().add(spacer);
    }

    private static VBox card(String heading) {
        Label head = new Label(heading);
        head.getStyleClass().add("page-subtitle");
        VBox card = new VBox(10);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.getStyleClass().add("card");
        card.getChildren().add(head);
        return card;
    }

    private void applySource(AppConfig.Source source, Label hintLabel) {
        session.switchSource(source); // 组合根内部重建核心实现 + 持久化
        hintLabel.setText(source == AppConfig.Source.REAL
                ? "当前:真实数据(Jolpica)—— 积分榜/赛历联网拉取,新闻来自官方 RSS"
                : "当前:Mock 演示 —— 积分榜/赛历为内嵌快照(第 13 站赛后),完全离线可用");
    }

    @Override
    public Node build() {
        return root;
    }
}
