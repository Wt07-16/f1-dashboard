package f1.ui.standings;

import f1.app.F1Session;
import f1.domain.model.DriverChampRow;
import f1.domain.model.RaceEntry;
import f1.domain.model.TeamChampRow;
import f1.ui.F1Page;
import f1.ui.util.Async;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Window;

import java.util.function.Consumer;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 积分榜页:车手榜 / 车队榜双表格 + 轮次回溯(看"第 N 站赛后"的积分)。
 * 全部数据经 {@link F1Session} 的 {@code standings()} 端口异步获取,
 * 本页不接触任何数据源细节。
 */
public final class StandingsPage implements F1Page {

    /** 一次加载的完整结果:最新已完赛轮次 + 两榜 */
    private record Snapshot(int latestRound, int shownRound,
                            List<DriverChampRow> drivers, List<TeamChampRow> teams) {
    }

    private final F1Session session;

    private final VBox root = new VBox(10);
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ComboBox<Integer> roundBox = new ComboBox<>();
    private final Label statusLabel = new Label();

    private final TableView<DriverChampRow> driverTable = new TableView<>();
    private final TableView<TeamChampRow> teamTable = new TableView<>();
    /** 两表共用占位文案:加载中 / 暂无 / 失败引导(Table 默认是英文,需自设) */
    private final Label placeholder = new Label("暂无数据");

    public StandingsPage(F1Session session) {
        this.session = session;
        buildUi();
    }

    private void buildUi() {
        root.setPadding(new Insets(18, 22, 18, 22));

        Label title = new Label("积分榜");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("🏆 车手与车队冠军积分 · 支持回溯到任意已完赛轮次");
        subtitle.getStyleClass().add("page-subtitle");
        root.getChildren().addAll(title, subtitle);

        // ---- 工具栏:模式 / 轮次 / 刷新 / 状态 ----
        ToggleButton driverBtn = new ToggleButton("车手榜");
        ToggleButton teamBtn = new ToggleButton("车队榜");
        driverBtn.getStyleClass().add("seg");
        teamBtn.getStyleClass().add("seg");
        driverBtn.setToggleGroup(modeGroup);
        teamBtn.setToggleGroup(modeGroup);
        driverBtn.setSelected(true);
        driverBtn.setOnAction(e -> showMode());
        teamBtn.setOnAction(e -> showMode());

        roundBox.setPromptText("选择轮次");
        roundBox.setPrefWidth(170);
        roundBox.setOnAction(e -> reload(false));

        Button refresh = new Button("↻ 刷新");
        refresh.getStyleClass().add("ghost");
        refresh.setOnAction(e -> reload(true)); // 手动刷新绕过缓存

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, driverBtn, teamBtn, roundBox, refresh, spacer, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(toolbar);

        // ---- 双表格叠加,按模式切换 ----
        buildDriverColumns();
        buildTeamColumns();
        driverTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        teamTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        placeholder.getStyleClass().add("muted");
        placeholder.setWrapText(true);
        driverTable.setPlaceholder(placeholder);
        teamTable.setPlaceholder(placeholder);
        // 点击行 → 资料卡(车手/车队各自弹对应卡)
        wireRowClick(driverTable, row -> {
            Window w = root.getScene() == null ? null : root.getScene().getWindow();
            ProfileDialog.showDriver(session, row, w);
        });
        wireRowClick(teamTable, row -> {
            Window w = root.getScene() == null ? null : root.getScene().getWindow();
            ProfileDialog.showTeam(session, row, w);
        });

        StackPane tables = new StackPane(driverTable, teamTable);
        StackPane.setMargin(tables, new Insets(0));
        VBox.setVgrow(tables, Priority.ALWAYS);
        root.getChildren().add(tables);

        showMode();
    }

    private void buildDriverColumns() {
        TableColumn<DriverChampRow, Integer> rank = new TableColumn<>("位置");
        rank.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().rank()));
        rank.setPrefWidth(60);

        TableColumn<DriverChampRow, String> num = new TableColumn<>("车号");
        num.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().driver().numberText()));

        TableColumn<DriverChampRow, String> name = new TableColumn<>("车手");
        name.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().driver().fullName()));
        name.setPrefWidth(240);

        TableColumn<DriverChampRow, String> team = new TableColumn<>("车队");
        team.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().driver().teamName() == null ? "—" : c.getValue().driver().teamName()));
        team.setPrefWidth(220);

        TableColumn<DriverChampRow, Integer> points = new TableColumn<>("积分");
        points.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().points()));
        points.setPrefWidth(70);

        TableColumn<DriverChampRow, Integer> wins = new TableColumn<>("胜场");
        wins.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().wins()));
        wins.setPrefWidth(60);

        driverTable.getColumns().addAll(rank, num, name, team, points, wins);
    }

    private void buildTeamColumns() {
        TableColumn<TeamChampRow, Integer> rank = new TableColumn<>("位置");
        rank.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().rank()));
        rank.setPrefWidth(60);

        TableColumn<TeamChampRow, String> name = new TableColumn<>("车队");
        name.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().team().name()));
        name.setPrefWidth(360);

        TableColumn<TeamChampRow, String> country = new TableColumn<>("总部所在地");
        country.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().team().nationality()));
        country.setPrefWidth(160);

        TableColumn<TeamChampRow, Integer> points = new TableColumn<>("积分");
        points.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().points()));
        points.setPrefWidth(70);

        TableColumn<TeamChampRow, Integer> wins = new TableColumn<>("胜场");
        wins.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().wins()));
        wins.setPrefWidth(60);

        teamTable.getColumns().addAll(rank, name, country, points, wins);
    }

    /** 给表格行挂单击事件(空行忽略),单击任意行即弹资料卡 */
    private static <T> void wireRowClick(TableView<T> table, Consumer<T> onClick) {
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1 && !row.isEmpty() && !e.isStillSincePress()) {
                    onClick.accept(row.getItem());
                }
            });
            return row;
        });
    }

    private void showMode() {
        boolean driverMode = driverModeSelected();
        driverTable.setVisible(driverMode);
        driverTable.setManaged(driverMode);
        teamTable.setVisible(!driverMode);
        teamTable.setManaged(!driverMode);
    }

    private boolean driverModeSelected() {
        ToggleButton btn = (ToggleButton) modeGroup.getSelectedToggle();
        return btn == null || "车手榜".equals(btn.getText());
    }

    /** 拉取:赛历(确定已完赛轮次)+ 两榜。force=true 跳过缓存(「刷新」按钮) */
    private void reload(boolean force) {
        Integer selected = roundBox.getValue(); // 在 FX 线程取值,别让后台线程读控件
        setStatus("⏳ 加载中…", false);
        placeholder.setText("⏳ 加载中…");
        Async.run(session.executor(), () -> {
            List<RaceEntry> schedule = session.scheduleCached(force);
            int latest = 0;
            for (RaceEntry race : schedule) {
                if (race.completed()) {
                    latest = race.round();
                }
            }
            int target = selected == null ? latest : selected;
            if (target < 1 || target > latest) {
                target = latest;
            }
            if (latest == 0) {
                return new Snapshot(0, 0, List.of(), List.of());
            }
            // 两榜互不依赖:并行拉取,总耗时取较长者而非相加
            final int shown = target; // lambda 只能捕获 effectively-final 变量
            var pool = session.executor();
            var drivers = CompletableFuture.supplyAsync(
                    () -> session.driverStandingsCached(shown, force), pool);
            var teams = CompletableFuture.supplyAsync(
                    () -> session.teamStandingsCached(shown, force), pool);
            return new Snapshot(latest, target, drivers.join(), teams.join());
        }, this::onLoaded, this::onError);
    }

    private void onLoaded(Snapshot snap) {
        List<Integer> options = new java.util.ArrayList<>();
        for (int i = 1; i <= snap.latestRound(); i++) {
            options.add(i);
        }
        roundBox.getItems().setAll(options);
        roundBox.setValue(snap.shownRound());
        roundBox.setPromptText("第 " + snap.latestRound() + " 站后(最新)");

        driverTable.getItems().setAll(snap.drivers());
        teamTable.getItems().setAll(snap.teams());
        placeholder.setText("暂无数据");
        setStatus("✓ 第 " + snap.shownRound() + " 站赛后积分", false);
    }

    private void onError(Throwable t) {
        setStatus("⚠ " + t.getMessage(), true);
        // 旧数据可能来自另一个数据源/轮次,失败时清空,占位区给重试引导
        driverTable.getItems().clear();
        teamTable.getItems().clear();
        placeholder.setText("⚠ " + t.getMessage() + "\n—— 点右上「↻ 刷新」重试");
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
        // 每次进入页面刷新一次;60 秒内直接命中缓存,瞬时显示
        reload(false);
    }
}
