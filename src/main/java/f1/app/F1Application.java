package f1.app;

import f1.domain.model.RaceEntry;
import f1.ui.RootView;
import f1.ui.notify.Notifier;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaFX 应用宿主 —— 组合根:创建共享线程池、读取配置、
 * 组装 {@link F1Session}(内部再装配数据源)与桌面通知,交给 UI。
 * UI 拿到的只是 domain 接口与门面,不知道也不关心数据从哪来。
 */
public final class F1Application extends Application {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;

    /** 距开赛多久以内值得弹"开赛提醒"(每站一次) */
    private static final Duration REMIND_WINDOW = Duration.ofMinutes(90);

    /** 退出时等清理的上限;超时也照样结束进程,绝不让用户去强制退出 */
    private static final long SHUTDOWN_GRACE_MS = 500;

    private ScheduledExecutorService executor;
    private F1Session session;
    private Notifier notifier;
    private Stage stage;

    /** 退出只走一次(叉号 / Cmd+Q / 托盘菜单可能先后触发) */
    private final AtomicBoolean quitting = new AtomicBoolean();

    @Override
    public void start(Stage stage) {
        // 并行拉取(两榜/两个 RSS 源)会占多个线程,留足余量
        executor = Executors.newScheduledThreadPool(8, r -> {
            Thread t = new Thread(r, "f1-data");
            t.setDaemon(true);
            return t;
        });

        AppConfig config = AppConfig.load();
        session = new F1Session(config, executor);
        notifier = new Notifier(config);

        RootView root = new RootView(session, notifier);
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(RootView.class.getResource("theme.css").toExternalForm());

        this.stage = stage;
        stage.setTitle("F1 Dashboard — 实时赛况");
        stage.setMinWidth(1000);
        stage.setMinHeight(680);
        stage.setScene(scene);
        // 点叉号 = 退出整个应用(含菜单栏托盘),走自己的退出路径
        stage.setOnCloseRequest(e -> quit());
        stage.show();

        scheduleRaceReminders();
        // 启动预取:窗口先出来,常用数据在后台预热,首次点进页面更快
        executor.execute(session::warmUp);
    }

    /**
     * 后台开赛提醒:每 4 分钟查一次赛历,若下一场正赛将在 90 分钟内开跑
     * 且本场还没提醒过 → 弹桌面通知。数据源(真实/Mock)都用各自赛历;
     * 无托盘环境空转(网络定时任务照常,只是通知不弹)。
     */
    private void scheduleRaceReminders() {
        Set<Integer> remindedRounds = ConcurrentHashMap.newKeySet();
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
        executor.scheduleWithFixedDelay(() -> {
            try {
                Instant now = Instant.now();
                for (RaceEntry race : session.season().schedule()) {
                    if (race.completed() || race.startInstant() == null
                            || !remindedRounds.add(race.round())) {
                        continue;
                    }
                    long untilMin = Duration.between(now, race.startInstant()).toMinutes();
                    if (untilMin <= 0 || untilMin > REMIND_WINDOW.toMinutes()) {
                        remindedRounds.remove(race.round()); // 还没进窗口,下轮再看
                        continue;
                    }
                    String localStart = time.format(race.startInstant().atZone(ZoneId.systemDefault()));
                    notifier.notify("⏰ 开赛提醒",
                            race.raceName() + " 将于 " + localStart + " 开跑(约 " + untilMin
                                    + " 分钟后)—— 切到「实时排名」页可看近实时赛况");
                }
            } catch (Throwable ignored) {
                // 网络抖动等不打断定时任务
            }
        }, 0, 4, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        // Cmd+Q、托盘的"退出"都会落到这里,汇入同一条退出路径
        quit();
    }

    /**
     * 唯一的退出路径 —— 保证"点叉号即退出",不依赖 JavaFX 的正常退出流程。
     *
     * 为什么不能只靠默认流程:macOS 上 AWT 托盘与 JavaFX 混用时,在 FX 线程里调
     * SystemTray.remove() 会走 CTrayIcon.dispose → Window.dispose → EventQueue.invokeAndWait
     * 去等 AWT 的 EDT,而 EDT 那边正卡在 native 的 CInputMethod.getNativeLocale() 上等
     * AppKit 主线程(被 JavaFX 占着)—— 两边永久互等,stop() 再也返回不了,
     * 进程被 main / JavaFX-Launcher / AWT-Shutdown 等非守护线程吊住,只能强制退出。
     *
     * 对策:清理挪到守护线程并限时等待,最后无条件 halt —— 清理成败都不影响退出。
     */
    private void quit() {
        if (!quitting.compareAndSet(false, true)) {
            return;
        }
        if (stage != null && stage.isShowing()) {
            stage.hide(); // 先让窗口消失:清理再慢,观感上也是"点了就关"
        }
        Thread cleanup = new Thread(this::releaseResources, "f1-shutdown");
        cleanup.setDaemon(true);
        cleanup.start();
        try {
            cleanup.join(SHUTDOWN_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().halt(0); // 不等待任何线程(含上面卡住的那几个)
    }

    /** 释放资源;逐项兜底,任何一项失败或卡住都不该拦着退出 */
    private void releaseResources() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
            // 退出阶段无碍
        }
        try {
            if (executor != null) {
                executor.shutdownNow();
            }
        } catch (Throwable ignored) {
            // 退出阶段无碍
        }
        try {
            if (notifier != null) {
                notifier.close();
            }
        } catch (Throwable ignored) {
            // 退出阶段无碍
        }
    }

    static void launchApp(String[] args) {
        launch(args);
    }
}
