package f1.ui.charts;

import f1.app.F1Session;
import f1.domain.model.Driver;
import f1.domain.model.LapSample;
import f1.domain.model.RaceEntry;
import f1.ui.F1Page;
import f1.ui.util.Async;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 圈速对比页:选一站已完赛大奖赛,叠加 1-2 名车手的"每圈用时"折线。
 * 纵轴数值越小越快(曲线低 = 快圈),进站圈掉速、冲刺圈一目了然;
 * 车手只画他实际有记录的圈(退赛即提前断线),单击图例可显隐任一条线。
 * 数据来自 F1Session 的逐圈缓存(首次按站拉取后不再重复请求);
 * 图表是纯 JavaFX LineChart + CSS 主题,无第三方依赖。
 */
public final class LapChartsPage implements F1Page {

    /** 下拉里的一个可选车手;兜底 ID 也能显示(未知花名册车手直接显示大写 ID) */
    private record Choice(String id, String num, String label) {
        @Override
        public String toString() {
            return (num == null || num.isBlank() ? "" : "#" + num + "  ") + label;
        }
    }

    private final F1Session session;
    private final VBox root = new VBox(14);

    private final ComboBox<Integer> roundBox = new ComboBox<>();
    private final ComboBox<Choice> driverA = new ComboBox<>();
    private final ComboBox<Choice> driverB = new ComboBox<>();
    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
    private final Label statusLabel = new Label();
    private final Label emptyHint = new Label();

    private int loadedRound = -1;
    private boolean built;

    public LapChartsPage(F1Session session) {
        this.session = session;
    }

    @Override
    public Node build() {
        if (built) {
            return root;
        }
        built = true;
        root.setPadding(new Insets(20, 24, 20, 24));

        Label title = new Label("圈速对比");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("📈 单圈用时走势 · 同场对比 1-2 名车手,曲线越低越快");
        subtitle.getStyleClass().add("page-subtitle");
        root.getChildren().addAll(title, subtitle);

        // ---- 工具栏:站 / 车手 A / 车手 B ----
        roundBox.setPrefWidth(150);
        roundBox.setCellFactory(cb -> roundCell(true));
        roundBox.setButtonCell(roundCell(false));
        roundBox.setOnAction(e -> {
            if (roundBox.getValue() != null) {
                loadRound(roundBox.getValue());
            }
        });

        driverA.setPrefWidth(220);
        driverA.setPromptText("车手 A(必选)");
        driverA.setOnAction(e -> rebuildChart());

        driverB.setPrefWidth(200);
        driverB.setPromptText("＋ 车手 B(可对比)");
        driverB.setOnAction(e -> rebuildChart());

        var clearB = new Button("✕");
        clearB.getStyleClass().add("ghost");
        clearB.setTooltip(new Tooltip("移除车手 B,回到单线"));
        clearB.setOnAction(e -> driverB.setValue(null));

        statusLabel.getStyleClass().add("status-text");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8,
                new Label("站次"), roundBox,
                new Label("车手 A"), driverA,
                new Label("对比"), driverB, clearB,
                spacer, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(toolbar);

        // ---- 图区(轴配置 ------- y 单位为秒,数值越小越快,不强制含 0)
        xAxis.setLabel("圈数");
        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number v) {
                return String.valueOf(Math.round(v.doubleValue()));
            }

            @Override
            public Number fromString(String s) {
                return Double.parseDouble(s);
            }
        });
        yAxis.setLabel("单圈用时");
        yAxis.setForceZeroInRange(false);
        yAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number v) {
                return lapText(v.doubleValue());
            }

            @Override
            public Number fromString(String s) {
                return parseLapSeconds(s);
            }
        });
        chart.setLegendSide(Side.BOTTOM);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        chart.setPrefHeight(480);
        chart.setTitle(null);

        emptyHint.getStyleClass().add("empty-hint");
        emptyHint.setWrapText(true);
        emptyHint.setMaxWidth(620);
        emptyHint.setText("选择一站已完赛的大奖赛,即可叠加两名车手的逐圈用时折线。\n"
                + "进站圈会明显掉速,冲刺圈往往全场最快;退赛车手的线会提前结束。");
        StackPane chartPane = new StackPane(chart, emptyHint);
        VBox.setVgrow(chartPane, Priority.ALWAYS);
        root.getChildren().add(chartPane);
        return root;
    }

    /** 轮次下拉的显示:数值转 "第 N 站"(支持 cell/button 两种场景) */
    private static ListCell<Integer> roundCell(boolean inList) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : "第 " + v + " 站");
            }
        };
    }

    // ================= 数据加载 =================

    /** 每次进入页面静默同步"已完赛站次"下拉(不打断正在看的图;首进自动选最新站) */
    @Override
    public void onShown() {
        refreshRoundList();
    }

    private void refreshRoundList() {
        Async.run(session.executor(), () -> {
            List<RaceEntry> schedule = session.season().schedule();
            int latest = 0;
            for (RaceEntry race : schedule) {
                if (race.completed()) {
                    latest = Math.max(latest, race.round());
                }
            }
            return latest;
        }, latest -> {
            if (latest == 0) {
                if (roundBox.getItems().isEmpty()) {
                    setStatus("⚠️ 本赛季还没有已完赛的站次,暂时无逐圈数据");
                }
                return;
            }
            List<Integer> options = new ArrayList<>();
            for (int i = 1; i <= latest; i++) {
                options.add(i);
            }
            roundBox.setItems(FXCollections.observableArrayList(options));
            if (roundBox.getValue() == null) {
                roundBox.setValue(latest); // 触发 loadRound
            } else if (roundBox.getValue() > latest) {
                roundBox.setValue(latest);
            }
        }, error -> {
            if (roundBox.getItems().isEmpty()) {
                setStatus("⚠️ 赛历获取失败:" + error.getMessage());
            }
        });
    }

    /** 加载某站逐圈数据 → 确定参赛车手名单 → 默认选该站第一名为车手 A */
    private void loadRound(int round) {
        if (round == loadedRound && !roundBox.getItems().isEmpty()) {
            return;
        }
        loadedRound = round;
        setStatus("⏳ 加载第 " + round + " 站逐圈数据(首次约几秒)…");
        emptyHint.setText("⏳ 逐圈数据加载中…");
        Async.run(session.executor(), () -> session.lapsCached(round),
                laps -> onLapsLoaded(round, laps),
                error -> {
                    emptyHint.setText("第 " + round + " 站逐圈数据不可用:" + error.getMessage());
                    setStatus("⚠️ 加载失败:" + error.getMessage());
                });
    }

    /** 圈速文件到手:按完赛名次生成车手下拉,默认冠军 = A */
    private void onLapsLoaded(int round, List<LapSample> laps) {
        setStatus("✓ 第 " + round + " 站 · " + laps.size() + " 条逐圈记录");
        emptyHint.setText("选择一名车手开始对比 —— 曲线越低 = 圈速越快");
        List<Choice> participants = participants(laps);
        if (participants.isEmpty()) {
            emptyHint.setText("这一站没有逐圈记录");
            return;
        }
        driverA.setItems(FXCollections.observableArrayList(participants));
        driverB.setItems(FXCollections.observableArrayList(participants));
        driverA.setValue(participants.get(0));
        driverB.setValue(null);
        rebuildChart();
    }

    /** 该站有记录的车手,按各自"最后一圈名次"排序(≈最终排位);未知车手兜底显示 ID */
    private List<Choice> participants(List<LapSample> laps) {
        Map<String, Driver> roster = new HashMap<>();
        for (Driver d : session.driverRoster()) {
            roster.put(d.id(), d);
        }
        // id -> (最后一圈, 末圈名次)
        Map<String, int[]> last = new LinkedHashMap<>();
        for (LapSample s : laps) {
            last.compute(s.driverId(), (id, cur) -> {
                int[] arr = cur == null ? new int[]{0, 0} : cur;
                if (s.lap() > arr[0] || (s.lap() == arr[0] && (arr[1] == 0 || s.position() < arr[1]))) {
                    arr[0] = s.lap();
                    arr[1] = s.position();
                }
                return arr;
            });
        }
        List<String> ids = last.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> {
                    int[] v = e.getValue();
                    return v[1] == 0 ? Integer.MAX_VALUE : v[1];
                }).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
        List<Choice> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Driver d = roster.get(id);
            if (d != null) {
                out.add(new Choice(id, d.numberText(), d.shortName()));
            } else {
                out.add(new Choice(id, "", id.toUpperCase()));
            }
        }
        return out;
    }

    /** 用当前选择重建两条折线(数据已在页内,纯内存计算) */
    private void rebuildChart() {
        chart.getData().clear();
        Integer round = roundBox.getValue();
        Choice a = driverA.getValue();
        if (round == null || a == null) {
            return;
        }
        List<LapSample> laps = safeLaps(round);
        if (laps == null) {
            return;
        }
        var series = new ArrayList<XYChart.Series<Number, Number>>(2);
        series.add(seriesOf(a, laps));
        if (driverB.getValue() != null && !driverB.getValue().id().equals(a.id())) {
            series.add(seriesOf(driverB.getValue(), laps));
        }
        chart.getData().setAll(series);
        // 图例点击可显隐,默认全显
        for (var s : series) {
            s.getNode().setOnMouseClicked(e -> toggleSeries(s));
        }
    }

    private void toggleSeries(XYChart.Series<Number, Number> s) {
        boolean show = !s.getNode().isVisible();
        s.getNode().setVisible(show);
        for (XYChart.Data<Number, Number> d : s.getData()) {
            d.getNode().setVisible(show);
        }
    }

    private List<LapSample> safeLaps(int round) {
        try {
            return session.lapsCached(round); // 已加载过,命中缓存
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 一名车手的折线:有记录的圈才有点;跳圈(极少)不影响连线 */
    private XYChart.Series<Number, Number> seriesOf(Choice choice, List<LapSample> laps) {
        var s = new XYChart.Series<Number, Number>();
        s.setName(choice.num() == null || choice.num().isBlank() ? choice.label()
                : "#" + choice.num() + " " + choice.label());
        List<LapSample> mine = laps.stream()
                .filter(x -> x.driverId().equals(choice.id()))
                .sorted(Comparator.comparingInt(LapSample::lap))
                .toList();
        for (LapSample sample : mine) {
            Double secs = parseLapSeconds(sample.time());
            if (secs == null) {
                continue;
            }
            XYChart.Data<Number, Number> point = new XYChart.Data<>(sample.lap(), secs);
            Tooltip.install(point.getNode(), new Tooltip("第 " + sample.lap() + " 圈 · " + sample.time()));
            s.getData().add(point);
        }
        return s;
    }

    // ================= 时间换算 =================

    /** "1:28.470" → 88.47 秒;解析失败返回 null(该点跳过) */
    static Double parseLapSeconds(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            int colon = t.indexOf(':');
            if (colon < 0) {
                return Double.parseDouble(t);
            }
            int min = Integer.parseInt(t.substring(0, colon));
            double rest = Double.parseDouble(t.substring(colon + 1));
            return min * 60.0 + rest;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 88.47 秒 → "1:28.5" 刻度文案 */
    static String lapText(double seconds) {
        int whole = (int) Math.round(seconds * 10.0);
        int m = whole / 600;
        int rest = whole % 600;
        return m + ":" + String.format("%02d.%d", rest / 10, rest % 10);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("error-text");
    }
}
