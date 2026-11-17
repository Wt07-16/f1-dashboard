package f1.ui.calendar;

import f1.app.F1Session;
import f1.domain.model.FinishingPosition;
import f1.domain.model.RaceEntry;
import f1.ui.F1Page;
import f1.ui.util.Async;
import f1.ui.util.StateCard;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 赛历页:全季分站卡片(已完赛 / 下一场 / 未开始)+ 下一场开赛倒计时(每秒走)。
 * 已完赛轮次可点开看该站正赛成绩。
 */
public final class CalendarPage implements F1Page {

    private final F1Session session;

    private final VBox root = new VBox(10);
    private final Label statusLabel = new Label();
    private final VBox raceList = new VBox(8);
    private final Label countdownLabel = new Label("—");
    private final Label heroRaceName = new Label();
    private final Label heroMeta = new Label();
    private final Label heroHint = new Label("下一场开赛倒计时");
    private final VBox heroCard = new VBox(4);

    private List<RaceEntry> races = List.of();
    private Instant countdownTarget;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            tickCountdown();
        }
    };

    public CalendarPage(F1Session session) {
        this.session = session;
        buildUi();
    }

    private void buildUi() {
        root.setPadding(new Insets(18, 22, 18, 22));
        root.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label("赛历");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("🗓️ 2026 赛季 23 站 · 所有时间为本地时区");
        subtitle.getStyleClass().add("page-subtitle");
        root.getChildren().addAll(title, subtitle);

        // 状态行(加载/错误)
        statusLabel.getStyleClass().add("loading-text");
        root.getChildren().add(statusLabel);

        // 下一场大卡
        heroHint.getStyleClass().add("countdown-label");
        heroHint.setMaxWidth(Double.MAX_VALUE);
        heroRaceName.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
        heroMeta.getStyleClass().add("muted");
        heroCard.getStyleClass().add("hero-card");
        heroCard.getChildren().addAll(heroHint, heroRaceName, heroMeta);

        HBox heroRow = new HBox(16, heroCard, countdownLabel);
        heroRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heroCard, Priority.ALWAYS);
        countdownLabel.getStyleClass().add("countdown");
        countdownLabel.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(heroRow);

        // 分站列表(滚动)
        raceList.setMaxWidth(Double.MAX_VALUE);
        ScrollPane scroll = new ScrollPane(raceList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);
    }

    private void tickCountdown() {
        if (countdownTarget == null) {
            return;
        }
        Duration d = Duration.between(Instant.now(), countdownTarget);
        if (d.isNegative() || d.isZero()) {
            countdownLabel.setText("开赛在即!🎬");
            timer.stop();
            return;
        }
        long days = d.toDays();
        long h = d.toHoursPart();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        countdownLabel.setText(days > 0
                ? String.format("%d 天 %02d:%02d:%02d", days, h, m, s)
                : String.format("%02d:%02d:%02d", h, m, s));
    }

    /** 重取赛历并重绘(进入页面/刷新) */
    private void reload() {
        statusLabel.setText("⏳ 加载赛历中…");
        statusLabel.getStyleClass().removeAll("error-text", "loading-text");
        statusLabel.getStyleClass().add("loading-text");
        Async.run(session.executor(), () -> session.season().schedule(), this::onLoaded, this::onError);
    }

    private void onLoaded(List<RaceEntry> list) {
        races = list;
        statusLabel.setText("");
        raceList.getChildren().clear();

        // 找下一场(第一个未完赛)
        RaceEntry next = null;
        for (RaceEntry race : races) {
            if (!race.completed()) {
                next = race;
                break;
            }
        }

        if (next == null) {
            heroHint.setText("本赛季已全部结束");
            heroRaceName.setText("感谢收看 🏁");
            heroMeta.setText("");
            countdownLabel.setText("");
            countdownTarget = null;
            timer.stop();
        } else {
            heroHint.setText("下一场开赛倒计时");
            heroRaceName.setText("第 " + next.round() + " 站 · " + next.raceName());
            heroMeta.setText(next.localStartText() + "  " + locationText(next));
            countdownTarget = next.startInstant();
            timer.start();
        }

        // 列表(不含 hero 里的下一场,避免重复)
        for (RaceEntry race : races) {
            if (race == next) {
                continue;
            }
            raceList.getChildren().add(buildRaceCard(race));
        }
    }

    private Node buildRaceCard(RaceEntry race) {
        HBox card = new HBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);

        Label round = new Label("R" + race.round());
        round.getStyleClass().add("round-chip");
        if (race.completed()) {
            round.getStyleClass().add("round-chip-plain");
        }

        VBox info = new VBox(3);
        info.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label name = new Label(race.raceName());
        name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ececf2;");
        Label meta = new Label(locationText(race));
        meta.getStyleClass().add("muted");
        info.getChildren().addAll(name, meta);

        Label chip;
        if (race.completed()) {
            chip = new Label("✓ 已完赛");
            chip.getStyleClass().add("chip chip-done");
        } else {
            chip = new Label("未开始 · " + race.localStartText());
            chip.getStyleClass().add("chip");
        }
        card.getChildren().addAll(round, info, chip);

        if (race.completed()) {
            Button result = new Button("查看成绩");
            result.getStyleClass().add("ghost");
            result.setOnAction(e -> openResults(race));
            card.getChildren().add(result);
        } else {
            card.setOpacity(0.92);
        }
        return card;
    }

    private String locationText(RaceEntry race) {
        String circuit = race.circuitName() == null || race.circuitName().isBlank() ? ""
                : race.circuitName();
        StringBuilder sb = new StringBuilder();
        if (race.locality() != null && !race.locality().isBlank()) {
            sb.append(race.locality()).append(" · ");
        }
        if (race.country() != null && !race.country().isBlank()) {
            sb.append(race.country());
        }
        if (!circuit.isEmpty()) {
            sb.append(" · ").append(circuit);
        }
        return sb.toString();
    }

    private void onError(Throwable t) {
        statusLabel.getStyleClass().removeAll("error-text", "loading-text");
        statusLabel.getStyleClass().add("error-text");
        statusLabel.setText("⚠ " + t.getMessage());
        // 内容区不再全空白:展示原因卡 + 重试入口
        raceList.getChildren().setAll(StateCard.error(t.getMessage() + " —— 赛历需要联网", this::reload));
    }

    /** 单站成绩弹窗:异步加载该轮正赛成绩 */
    private void openResults(RaceEntry race) {
        Stage dialog = new Stage();
        dialog.initOwner(root.getScene().getWindow());
        dialog.setTitle(race.raceName() + " · 正赛成绩");

        Label loading = new Label("⏳ 加载成绩中…");
        loading.getStyleClass().add("loading-text");

        TableView<FinishingPosition> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("暂无数据"));

        TableColumn<FinishingPosition, Integer> pos = new TableColumn<>("位置");
        pos.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().position()));
        pos.setPrefWidth(60);
        TableColumn<FinishingPosition, String> driver = new TableColumn<>("车手");
        driver.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().driver().fullName()));
        driver.setPrefWidth(220);
        TableColumn<FinishingPosition, String> team = new TableColumn<>("车队");
        team.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().driver().teamName() == null ? "—" : c.getValue().driver().teamName()));
        team.setPrefWidth(200);
        TableColumn<FinishingPosition, Integer> points = new TableColumn<>("积分");
        points.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().points()));
        points.setPrefWidth(60);
        TableColumn<FinishingPosition, String> result = new TableColumn<>("成绩 / 状态");
        result.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().time() == null ? c.getValue().status() : c.getValue().time()));
        result.setPrefWidth(140);
        table.getColumns().addAll(pos, driver, team, points, result);

        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setVisible(false);

        VBox box = new VBox(8, loading, table, error);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0e0e12;");
        VBox.setVgrow(table, Priority.ALWAYS);
        Scene scene = new Scene(box, 760, 520);
        scene.getStylesheets().add(getClass().getResource("/f1/ui/theme.css").toExternalForm());
        dialog.setScene(scene);
        dialog.show();

        Async.run(session.executor(), () -> session.season().raceResults(race.round()),
                rows -> {
                    loading.setVisible(false);
                    table.getItems().setAll(rows);
                },
                t -> {
                    loading.setVisible(false);
                    error.setVisible(true);
                    error.setText("⚠ " + t.getMessage());
                });
    }

    @Override
    public Node build() {
        return root;
    }

    @Override
    public void onShown() {
        reload();
    }

    @Override
    public void onHidden() {
        timer.stop();
    }
}
