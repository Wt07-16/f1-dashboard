package f1.ui.live;

import f1.app.F1Session;
import f1.domain.model.Driver;
import f1.domain.model.PositionUpdate;
import f1.domain.model.RaceEntry;
import f1.domain.model.Session;
import f1.domain.port.LivePlayback;
import f1.domain.port.LiveTimingProvider;
import f1.ui.F1Page;
import f1.ui.notify.Notifier;
import f1.ui.util.Async;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时排名页 —— 三种数据流共用一张表与一套状态:
 *   1. 🎬 Mock 演示   纯本地模拟比赛(离线可用)
 *   2. 🎞️ 回放最近一站  真实逐圈记录按时间轴重放
 *   3. 📡 真实近实时    比赛进行中自动接入 Jolpica 轮询(按圈更新)
 *
 * 页面只知道 LiveTimingProvider 接口;引擎从 F1Session 按需创建,来源差异不进 UI。
 * 引擎回调发生在工作线程,这里做合并分发:一个 FX 拍内的多批更新只刷一次表。
 */
public final class LivePage implements F1Page {

    /** 视作"比赛进行中"的窗口:正赛起跑后 3 小时内 */
    private static final java.time.Duration RACE_WINDOW = java.time.Duration.ofHours(3);
    /** 非正赛场次(练习/排位/冲刺)视作"正在进行"的窗口:起跑后 2 小时内 */
    private static final java.time.Duration SESSION_WINDOW = java.time.Duration.ofHours(2);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final F1Session session;
    private final VBox root = new VBox(14);
    private boolean built;

    // ---- 状态区 ----
    private final Circle dot = new Circle(6);
    private final Label statusLabel = new Label();
    private final Label captionLabel = new Label();
    private final Label clockLabel = new Label();
    private Button stopBtn;

    // ---- 引擎(仅 FX 线程访问) ----
    private LiveTimingProvider engine;
    private boolean engineEnding;
    private boolean autoLiveStopped;
    /** 已自动接入过的站次(防止流自然结束后被巡查反复重连);-1 = 无 */
    private int autoLiveRound = -1;

    /** 当前正在进行的非正赛场次(冲刺赛/排位赛/练习赛);无则 null */
    private Session runningSession;
    /** runningSession 所属的站(用于告知正赛何时开始) */
    private RaceEntry sessionRace;

    /** 停留本页期间的赛历巡查:正赛开跑后无需重进页面即可自动接入 */
    private Timeline scheduleWatch;

    // ---- 赛历信息(onShown 时后台刷新,带 10s 缓存) ----
    private int latestCompletedRound;
    private int runningRound;
    private Instant lastScheduleFetch;

    // ---- 表格 ----
    private final Map<String, LiveRow> byId = new LinkedHashMap<>();
    private final Map<String, Driver> roster = new HashMap<>();
    private final ObservableList<LiveRow> items = FXCollections.observableArrayList(
            row -> new Observable[]{row.pos, row.lapTime, row.self, row.gen});
    private final TableView<LiveRow> table = new TableView<>(items);
    private final Label emptyHint = new Label();

    // ---- 前三名变动 → 桌面通知 ----
    private final Notifier notifier;
    private final List<String> podiumBaseline = new ArrayList<>(3); // 名次 1-3 显示名
    private boolean podiumSeeded;      // 首帧只建基线不通知(否则开局必炸一条)
    private long lastPodiumNotifyMs;

    // ---- 回放倍速(仅回放引擎接入时显示;演示/真实近实时无此能力) ----
    private static final double[] SPEED_PRESETS = {0.5, 1.0, 2.0, 4.0};
    private final ToggleGroup speedGroup = new ToggleGroup();
    private final Map<Double, ToggleButton> speedButtons = new HashMap<>();
    private HBox speedBar;

    // ---- 合并分发缓冲(引擎线程写入,FX 线程排出) ----
    private final List<PositionUpdate> pending = new ArrayList<>();
    private boolean draining;
    private final Object drainLock = new Object();

    public LivePage(F1Session session, Notifier notifier) {
        this.session = session;
        this.notifier = notifier;
    }

    @Override
    public Node build() {
        if (built) {
            return root;
        }
        built = true;
        root.setPadding(new Insets(20, 24, 20, 24));

        for (Driver d : session.driverRoster()) {
            roster.put(d.id(), d);
        }

        Label title = new Label("实时排名");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("比赛进行中自动接入近实时数据 · 平时可离线演示或回放真实赛况");
        subtitle.getStyleClass().add("page-subtitle");
        root.getChildren().add(title);

        // ---- 控制条 ----
        Button openf1Btn = new Button("🛰️ OpenF1 实时");
        openf1Btn.getStyleClass().add("btn-run");
        openf1Btn.setTooltip(new Tooltip("连接 OpenF1 实时数据流(覆盖练习/冲刺排位/冲刺/排位/正赛;非直播时段自动展示最近场次)"));
        Button demoBtn = new Button("🎬 Mock 演示");
        demoBtn.getStyleClass().add("btn-run");
        demoBtn.setTooltip(new Tooltip("本地模拟一场约 75 秒的比赛,无需网络"));
        Button replayBtn = new Button("🎞️ 回放最近一站");
        replayBtn.getStyleClass().add("btn-replay");
        replayBtn.setTooltip(new Tooltip("把最近完赛站的真实逐圈记录按时间轴重放(需网络)"));
        stopBtn = new Button("⏹ 停止");
        stopBtn.getStyleClass().add("btn-stop");
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> onUserStop());

        openf1Btn.setOnAction(e -> {
            autoLiveStopped = false;
            attach(session.openf1Live());
        });
        demoBtn.setOnAction(e -> {
            autoLiveStopped = false;
            attach(session.demoRace());
        });
        replayBtn.setOnAction(e -> {
            if (latestCompletedRound <= 0) {
                setStatus("⏳ 正在获取赛历,定位最近一站…", "run");
                fetchScheduleThen(() -> {
                    if (latestCompletedRound > 0) {
                        attach(session.replayRace(latestCompletedRound));
                    } else {
                        setStatus("⚠️ 还没有已完赛的站次可供回放", "err");
                    }
                });
            } else {
                attach(session.replayRace(latestCompletedRound));
            }
        });

        dot.getStyleClass().addAll("status-dot", "idle");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setText("空闲 —— 点「🛰️ OpenF1 实时」看真实赛况,或点「🎬 Mock 演示」离线开跑");
        captionLabel.getStyleClass().add("caption");
        captionLabel.setWrapText(true);
        clockLabel.getStyleClass().add("caption");
        clockLabel.setMinWidth(84);

        HBox topRow = new HBox(14);
        topRow.setPadding(new Insets(12, 16, 12, 16));
        topRow.getStyleClass().add("card");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        topRow.getChildren().addAll(openf1Btn, demoBtn, replayBtn, stopBtn, statusLabel, clockLabel);
        topRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(topRow);

        // 回放倍速条:默认隐藏,回放引擎接入时出现
        Label speedCaption = new Label("⏩ 回放速度");
        speedCaption.getStyleClass().add("caption");
        speedBar = new HBox(6);
        speedBar.setAlignment(Pos.CENTER_LEFT);
        speedBar.getChildren().add(speedCaption);
        for (double r : SPEED_PRESETS) {
            ToggleButton b = new ToggleButton(trimRate(r) + "×");
            b.getStyleClass().add("seg");
            b.setToggleGroup(speedGroup);
            b.setOnAction(e -> applyPlaybackSpeed());
            speedButtons.put(r, b);
            speedBar.getChildren().add(b);
        }
        speedButtons.get(1.0).setSelected(true);
        speedBar.setVisible(false);
        speedBar.setManaged(false);
        root.getChildren().add(speedBar);

        root.getChildren().add(captionLabel);

        // ---- 表格 ----
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("暂无数据"));
        table.setPrefHeight(430);
        table.setMinHeight(260);

        TableColumn<LiveRow, Number> posCol = new TableColumn<>("POS");
        posCol.setPrefWidth(56);
        posCol.setStyle("-fx-alignment: CENTER;");
        posCol.setCellValueFactory(c -> c.getValue().pos);

        TableColumn<LiveRow, LiveRow> driverCol = new TableColumn<>("车手");
        driverCol.setPrefWidth(210);
        driverCol.setCellValueFactory(c -> c.getValue().self);
        driverCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LiveRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    getStyleClass().remove("driver-cell");
                    setText(null);
                    setTooltip(null);
                } else {
                    getStyleClass().add("driver-cell");
                    setText((row.retired ? "⛔ " : "") + "#" + row.num + "  " + row.name);
                    setTooltip(row.retired
                            ? new Tooltip(row.fullName + " · " + row.team.get()
                                    + " · 完成第 " + row.lastLap + " 圈后退赛")
                            : new Tooltip(row.fullName + " · " + row.team.get()));
                }
            }
        });

        TableColumn<LiveRow, String> teamCol = new TableColumn<>("车队");
        teamCol.setPrefWidth(180);
        teamCol.setCellValueFactory(c -> c.getValue().team);
        teamCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    getStyleClass().remove("team-cell");
                    setText(null);
                } else {
                    getStyleClass().add("team-cell");
                    setText(t);
                }
            }
        });

        TableColumn<LiveRow, String> timeCol = new TableColumn<>("上一圈");
        timeCol.setPrefWidth(110);
        timeCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        timeCol.setCellValueFactory(c -> c.getValue().lapTime);
        timeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null || t.isBlank()) {
                    setText(null);
                } else {
                    setText(t);
                }
                getStyleClass().remove("live-time-cell");
                if (!empty && t != null && !t.isBlank()) {
                    getStyleClass().add("live-time-cell");
                }
            }
        });

        TableColumn<LiveRow, LiveRow> deltaCol = new TableColumn<>("位次变化");
        deltaCol.setPrefWidth(92);
        deltaCol.setStyle("-fx-alignment: CENTER;");
        deltaCol.setCellValueFactory(c -> c.getValue().self);
        deltaCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LiveRow row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().removeAll("delta-up", "delta-down", "delta-flat");
                if (empty || row == null) {
                    setText(null);
                    return;
                }
                if (row.retired) {
                    setText("–"); // 退赛:不再显示升降
                    getStyleClass().add("delta-flat");
                    return;
                }
                int delta = row.delta(); // 正 = 掉名次,负 = 升名次
                if (!row.hasHistory()) {
                    setText("–");
                    getStyleClass().add("delta-flat");
                } else if (delta < 0) {
                    setText("▲ " + (-delta));
                    getStyleClass().add("delta-up");
                } else if (delta > 0) {
                    setText("▼ " + delta);
                    getStyleClass().add("delta-down");
                } else {
                    setText("–");
                    getStyleClass().add("delta-flat");
                }
            }
        });

        //noinspection unchecked
        table.getColumns().setAll(List.of(posCol, driverCol, teamCol, timeCol, deltaCol));

        // 退赛车手整行灰显(醒目但不喧宾夺主)
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(LiveRow row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("retired-row");
                if (!empty && row != null && row.retired) {
                    getStyleClass().add("retired-row");
                }
            }
        });

        emptyHint.getStyleClass().add("empty-hint");
        emptyHint.setWrapText(true);
        emptyHint.setMaxWidth(560);
        emptyHint.setText("表格为空:点上方「🎬 Mock 演示」离线开一场模拟比赛;\n"
                + "「🎞️ 回放最近一站」重放真实赛况;\n"
                + "若比赛正在直播,本页会自动接入近实时位置更新。");
        StackPane tablePane = new StackPane(table, emptyHint);
        VBox.setVgrow(tablePane, Priority.ALWAYS);
        root.getChildren().add(tablePane);
        return root;
    }

    // ================= 引擎生命周期 =================

    /** 替换式启动:旧引擎先断开,新引擎接管(同一时刻只有一个流) */
    private void attach(LiveTimingProvider fresh) {
        stopEngine();
        byId.clear();
        items.clear();
        podiumSeeded = false;
        engineEnding = false;
        engine = fresh;
        setStatus("启动中…", "run");
        captionLabel.setText("初始化数据流…");
        // 倍速只对支持变速的流(回放引擎)可见,并即时应用当前选中档
        boolean speedable = fresh instanceof LivePlayback;
        speedBar.setVisible(speedable);
        speedBar.setManaged(speedable);
        if (speedable) {
            applyPlaybackSpeed();
        }
        fresh.connect(new LiveTimingProvider.Listener() {
            @Override
            public void onUpdates(List<PositionUpdate> updates) {
                enqueue(updates);
            }

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> onEngineStatus(message));
            }

            @Override
            public void onRetirement(String driverId, int atLap) {
                // 退赛判定已在数据侧完成;这里仅上平台线程呈现
                Platform.runLater(() -> onEngineRetirement(driverId, atLap));
            }

            @Override
            public void onRetirementRescinded(String driverId) {
                // 官方成绩确认误判(被套圈完赛者)→ 恢复为完赛行
                Platform.runLater(() -> onEngineRescind(driverId));
            }
        });
        stopBtn.setDisable(false);
    }

    /** 把当前选中的倍速应用到运行中的回放引擎(无变速能力时忽略) */
    private void applyPlaybackSpeed() {
        if (!(engine instanceof LivePlayback playback)) {
            return;
        }
        double selected = 1.0;
        for (var e : speedButtons.entrySet()) {
            if (e.getValue().isSelected()) {
                selected = e.getKey();
                break;
            }
        }
        playback.setSpeed(selected);
    }

    private void stopEngine() {
        if (engine != null) {
            engine.disconnect();
            engine = null;
        }
        engineEnding = false;
        stopBtn.setDisable(true);
        speedBar.setVisible(false);
        speedBar.setManaged(false);
    }

    /** "0.5" → "0.5";"1.0" → "1";"2.0" → "2" */
    private static String trimRate(double r) {
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private void onUserStop() {
        autoLiveStopped = true;
        stopEngine();
        clockLabel.setText("");
        setStatus("已停止 —— 表格保留最后状态", "idle");
        captionLabel.setText("引擎已断开,可点「🎬 Mock 演示」或「🎞️ 回放」重新开始");
    }

    /** 引擎自发结束(🏁/❌)后的自动清理 */
    private void onEngineStatus(String message) {        if (engine == null) {
            return; // 已手动停止,丢弃迟到消息
        }
        if (message.contains("🏁") || message.contains("结束") || message.contains("完成")) {
            setStatus(message, "done");
            engineEnding = true;
            PauseTransition pause = new PauseTransition(Duration.millis(2800));
            pause.setOnFinished(e -> {
                if (engineEnding) {
                    stopEngine();
                    clockLabel.setText("");
                    captionLabel.setText("数据流已结束 —— 「🎬 Mock 演示」再来一场,或「🎞️ 回放」看其它站");
                }
            });
            pause.play();
        } else if (message.contains("❌") || message.contains("⚠️")
                || message.contains("失败") || message.contains("不可用")) {
            setStatus(message, "err");
        } else {
            setStatus(message, "run");
        }
    }

    private void setStatus(String text, String dotClass) {
        statusLabel.setText(text);
        dot.getStyleClass().removeAll("idle", "run", "done", "err");
        dot.getStyleClass().add(dotClass);
    }

    // ================= 合并分发 =================

    private void enqueue(List<PositionUpdate> updates) {
        synchronized (drainLock) {
            pending.addAll(updates);
            if (draining) {
                return;
            }
            draining = true;
        }
        Platform.runLater(this::drain);
    }

    /** 一个 FX 拍内把积压的更新合并成一次表格刷新 */
    private void drain() {
        final List<PositionUpdate> batch;
        synchronized (drainLock) {
            batch = new ArrayList<>(pending);
            pending.clear();
            draining = false;
        }
        if (batch.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (PositionUpdate u : batch) {
            changed |= apply(u);
        }
        if (changed) {
            sortAndSet();
            maybeNotifyPodium();
        }
        clockLabel.setText("更新 " + LocalTime.now().format(CLOCK));
        if (engine != null) {
            captionLabel.setText(engine.describe());
        }
        if (!items.isEmpty()) {
            emptyHint.setManaged(false);
            emptyHint.setVisible(false);
        }
    }

    /** 官方成绩撤销退赛误判(典型:被套圈但冲线完赛者):恢复完赛行并重排 */
    private void onEngineRescind(String driverId) {
        if (engine == null) {
            return; // 已手动停止,丢弃迟到事件
        }
        LiveRow row = byId.get(driverId);
        if (row == null || !row.retired) {
            return;
        }
        row.unmarkRetired();
        setStatus("↩ 官方成绩确认:" + row.name + " 实为完赛(被套圈),已移回榜单", "run");
        sortAndSet();
    }

    /**
     * 前三名变动 → 桌面通知。只报"新进前三/前三易手",同车手 12 秒内只响一次;
     * 演示/回放/真实共用同一条规则(演示也顺便演示通知)。
     */
    private void maybeNotifyPodium() {
        if (notifier == null || !notifier.enabled() || items.size() < 3) {
            return;
        }
        List<String> top3 = items.stream()
                .filter(r -> !r.retired)
                .limit(3)
                .map(r -> r.name)
                .toList();
        if (!podiumSeeded) {
            podiumBaseline.clear();
            podiumBaseline.addAll(top3);
            podiumSeeded = true;
            return; // 首帧只建基线
        }
        List<String> gained = new ArrayList<>();
        for (int i = 0; i < top3.size(); i++) {
            if (i >= podiumBaseline.size() || !top3.get(i).equals(podiumBaseline.get(i))) {
                String name = top3.get(i);
                if (!gained.contains(name)) {
                    gained.add(name);
                }
            }
        }
        podiumBaseline.clear();
        podiumBaseline.addAll(top3);
        if (gained.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPodiumNotifyMs < 12_000) {
            return; // 节流窗口内:基线已更新,不重复响
        }
        lastPodiumNotifyMs = now;
        List<String> parts = new ArrayList<>(gained.size());
        for (String name : gained) {
            int pos = top3.indexOf(name) + 1;
            parts.add("▲ " + name + " 升至 P" + pos);
        }
        notifier.notify("🏁 实时名次变化", String.join(" · ", parts));
    }

    /** 引擎明确上报某车手退赛:标记 + 沉底重排(判定在数据侧完成,这里只呈现) */
    private void onEngineRetirement(String driverId, int atLap) {
        if (engine == null) {
            return; // 已手动停止,丢弃迟到事件
        }
        LiveRow row = byId.get(driverId);
        if (row == null || row.retired) {
            return;
        }
        row.markRetired(atLap);
        setStatus("⛔ " + row.name + " 退赛 —— 完成第 " + row.lastLap + " 圈后未再出圈", "run");
        sortAndSet();
    }

    /** 完赛按名次在前;退赛沉底,组内按退赛圈倒序(越晚退越靠前,接近官方非完赛排位) */
    private void sortAndSet() {
        List<LiveRow> sorted = new ArrayList<>(byId.values());
        Comparator<LiveRow> order = (a, b) -> {
            if (a.retired != b.retired) {
                return a.retired ? 1 : -1;
            }
            if (a.retired) {
                return Integer.compare(b.retireLap, a.retireLap);
            }
            return Integer.compare(a.pos.get(), b.pos.get());
        };
        sorted.sort(order.thenComparing(r -> r.name));
        items.setAll(sorted);
    }

    /** 应用一条更新;返回是否有变化(需要重排) */
    private boolean apply(PositionUpdate u) {
        LiveRow row = byId.get(u.driverId());
        if (row == null) {
            Driver d = roster.get(u.driverId());
            if (d == null) {
                d = session.findDynamicDriver(u.driverId());
            }
            row = new LiveRow(u.driverId(),
                    d == null ? u.driverId() : d.shortName(),
                    d == null || d.teamName() == null ? "—" : d.teamName(),
                    d == null ? "" : d.numberText(),
                    d == null ? u.driverId() : d.fullName(),
                    u.position());
            row.lapTime.set(u.lapTime() == null ? "" : u.lapTime());
            byId.put(u.driverId(), row);
            return true;
        }
        return row.apply(u);
    }

    // ================= 页面生命周期 =================

    @Override
    public void onShown() {
        if (captionLabel.getText().isBlank()) {
            captionLabel.setText("三种模式可随时切换;数据流的任何状态变化会显示在顶部");
        }
        startScheduleWatch(); // 停留本页期间持续跟进:正赛一开跑就自动接入
        fetchScheduleThen(this::applyLiveState);
    }

    @Override
    public void onHidden() {
        stopScheduleWatch(); // 离开页面即停巡查,不泄漏定时器
        stopEngine(); // 离开页面即断开数据流,不泄漏线程
        engineEnding = false;
    }

    /**
     * 依最新赛历决定此刻该做什么:
     * 无论是正赛(runningRound > 0)还是练习赛/冲刺排位/冲刺赛/排位赛(runningSession != null),
     * 只要处于比赛场次窗口内,一律自动接入 OpenF1 实时数据流。
     */
    private void applyLiveState() {
        if (engine != null || autoLiveStopped) {
            return; // 已有流在跑,或用户手动停过:不打扰
        }
        if (runningRound > 0 && runningRound != autoLiveRound) {
            setStatus("🟢 第 " + runningRound + " 站正赛进行中 —— 已自动接入 OpenF1 实时数据流",
                    "run");
            captionLabel.setText("OpenF1 每 5 秒刷新实时名次与单圈用时;若提示 401 请在「设置」页填入 Sponsor 凭据");
            autoLiveRound = runningRound; // 记住已接入,避免流结束后被反复重连
            attach(session.openf1Live());
        } else if (runningSession != null) {
            int sRound = sessionRace != null ? sessionRace.round() : 0;
            if (sRound != autoLiveRound) {
                setStatus("🟢 " + runningSession.kind().label()
                        + " 进行中 —— 已自动接入 OpenF1 实时数据流", "run");
                captionLabel.setText("OpenF1 覆盖练习赛/冲刺排位/冲刺赛/排位赛/正赛全部实时数据");
                autoLiveRound = sRound;
                attach(session.openf1Live());
            }
        }
    }

    /** 停留本页期间每 60 秒复查赛历(已有流在跑时不打扰) */
    private void startScheduleWatch() {
        if (scheduleWatch != null) {
            return;
        }
        scheduleWatch = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            if (engine == null) {
                fetchScheduleThen(this::applyLiveState);
            }
        }));
        scheduleWatch.setCycleCount(Timeline.INDEFINITE);
        scheduleWatch.play();
    }

    private void stopScheduleWatch() {
        if (scheduleWatch != null) {
            scheduleWatch.stop();
            scheduleWatch = null;
        }
    }

    /** 后台取一次赛历(10s 缓存,避免切页狂拉);回调在 FX 线程 */
    private void fetchScheduleThen(Runnable onDoneFx) {
        if (lastScheduleFetch != null
                && Instant.now().minusSeconds(10).isBefore(lastScheduleFetch)) {
            onDoneFx.run();
            return;
        }
        Async.run(session.executor(), session.season()::schedule,
                races -> {
                    lastScheduleFetch = Instant.now();
                    latestCompletedRound = 0;
                    runningRound = 0;
                    runningSession = null;
                    sessionRace = null;
                    Instant now = Instant.now();
                    for (RaceEntry r : races) {
                        if (r.completed()) {
                            latestCompletedRound = Math.max(latestCompletedRound, r.round());
                        } else if (r.startInstant() != null) {
                            Instant start = r.startInstant();
                            if (!now.isBefore(start) && now.isBefore(start.plus(RACE_WINDOW))
                                    && runningRound == 0) {
                                runningRound = r.round();
                            }
                        }
                        // 非正赛场次:平时只为说明"现在在跑什么";正赛窗口优先
                        if (runningSession == null) {
                            for (Session s : r.sessions()) {
                                if (s.start() != null && !now.isBefore(s.start())
                                        && now.isBefore(s.start().plus(SESSION_WINDOW))) {
                                    runningSession = s;
                                    sessionRace = r;
                                    break;
                                }
                            }
                        }
                    }
                    if (runningRound == 0) {
                        autoLiveRound = -1; // 已出正赛窗口:下一站重新允许自动接入
                    }
                    onDoneFx.run();
                },
                error -> setStatus("⚠️ 赛历获取失败:" + error.getMessage(), "err"));
    }

    // ================= 行模型 =================

    /** 表格行。位置/圈速用可观察属性(供列绑定与 extractor 刷新);
     * 位次历史(prevPos)普通字段即可,只在本类 drain 内读写。 */
    static final class LiveRow {
        final SimpleIntegerProperty pos = new SimpleIntegerProperty();
        final SimpleStringProperty lapTime = new SimpleStringProperty("");
        /** 供需要整行对象的列绑定(永不变更的静态对象) */
        final SimpleObjectProperty<LiveRow> self = new SimpleObjectProperty<>(this);
        /** 状态代次:退赛等"非位置/圈速"的状态变更时递增,驱动 TableView 整行重绘 */
        final SimpleIntegerProperty gen = new SimpleIntegerProperty();

        final String driverId;
        final String name;
        final String num;
        final String fullName;
        final SimpleStringProperty team = new SimpleStringProperty("");
        int prevPos = -1; // 上一次名次;尚无历史 = -1
        int lastLap;      // 该车手最近一次收到记录所处的圈
        boolean retired;  // 中途无后续圈速记录 → 视为退赛
        int retireLap;    // 判定退赛时数据已推进到的圈(组内排序依据:晚退赛靠前)

        LiveRow(String driverId, String name, String team, String num,
                String fullName, int initialPos) {
            this.driverId = driverId;
            this.name = name;
            this.team.set(team);
            this.num = num;
            this.fullName = fullName;
            this.pos.set(initialPos);
        }

        /** 标记退赛:停在其最后记录的位置,不再参与名次排序 */
        void markRetired(int atLap) {
            retired = true;
            retireLap = atLap;
            prevPos = -1; // 升降箭头对退赛不再有意义
            gen.set(gen.get() + 1); // 触发该行全部单元格重绘(⛔ 前缀/灰显/箭头置 "–")
        }

        /** 撤销退赛(官方成绩确认误判):回到完赛区,按停表位置排序;不再显示升降箭头 */
        void unmarkRetired() {
            retired = false;
            retireLap = 0;
            prevPos = -1;
            gen.set(gen.get() + 1);
        }

        /** 应用一条更新;返回是否影响展示(位置或圈速变化) */
        boolean apply(PositionUpdate u) {
            if (retired) {
                return false; // 退赛后再来记录 = 迟到数据,不再改变该行
            }
            boolean shown = false;
            if (u.lap() > lastLap) {
                lastLap = u.lap();
            }
            if (u.position() != pos.get()) {
                prevPos = pos.get(); // 名次变化:旧名次记入历史,用于升降箭头
                pos.set(u.position());
                shown = true;
            }
            if (u.lapTime() != null && !u.lapTime().isBlank()
                    && !u.lapTime().equals(lapTime.get())) {
                lapTime.set(u.lapTime());
                shown = true;
            }
            return shown;
        }

        boolean hasHistory() {
            return prevPos > 0;
        }

        /** 相对上一名次:正 = 掉名次,负 = 升名次 */
        int delta() {
            return prevPos <= 0 ? 0 : pos.get() - prevPos;
        }
    }
}
