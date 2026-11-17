package f1.data.jolpica;

import f1.domain.DataException;
import f1.domain.model.FinishingPosition;
import f1.domain.model.LapSample;
import f1.domain.model.PositionUpdate;
import f1.domain.port.LapHistoryProvider;
import f1.domain.port.LiveTimingProvider;
import f1.domain.port.SeasonProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实近实时流:比赛进行中轮询 Jolpica /laps.json。
 *
 * 每 POLL_INTERVAL 拉一次该轮逐圈数据;发现"新的一圈已发布"时,
 * 把该圈全体车手(按该圈 timings 的 position 排序)作为一批更新推送。
 * 同一圈内数据被再次拉到(轮询间隔小于数据发布节奏)时不重复推送。
 *
 * 结束判定:连续 NO_PROGRESS_LIMIT 次轮询圈数未增长,且已至少见过一圈 →
 * 认为比赛数据已发布完毕(回放/结束态);从未见过任何圈 → 提示"尚未开赛"。
 * 异常不中断线程:标记为"暂不可用",下轮自动重试(自愈)。
 *
 * 退赛与"被套圈完赛"的边界:比赛中途"新圈缺席"几乎必为退赛(被套圈者
 * 仍在跑,圈圈有记录);只有收官时被套圈者先于全场冲线,会短暂缺最后一圈。
 * 故数据静止(比赛结束)后拉一次官方成绩核对:误标者以 onRetirementRescinded
 * 撤销 —— 即时性好,事后又被官方名单校正,两头不误。
 */
public final class JolpicaLiveRace implements LiveTimingProvider {

    private static final long POLL_INTERVAL_MS = 8000;
    private static final int NO_PROGRESS_LIMIT = 6;

    private final LapHistoryProvider history;
    /** 官方成绩源(组合根注入;仅收官核对退赛名单用) */
    private final SeasonProvider results;
    private final int round;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "f1-jolpica-live");
                t.setDaemon(true);
                return t;
            });

    private volatile Listener listener;
    /** 上一圈已推送的圈号 */
    private final AtomicInteger lastPushedLap = new AtomicInteger();
    private final AtomicInteger staleCount = new AtomicInteger();
    private final AtomicInteger lastSeenLap = new AtomicInteger();
    private volatile boolean disconnected;
    /** 上一已推送圈的全体车手(用于退赛差分:新圈缺席 = 退赛) */
    private java.util.Set<String> prevLapDrivers = java.util.Set.of();
    /** 本局已上报的退赛车手(收官核对用;仅 poll 单线程访问) */
    private final Set<String> reportedRetired = new HashSet<>();
    private boolean corrected; // 收官核对只做一次

    public JolpicaLiveRace(LapHistoryProvider history, SeasonProvider results, int round) {
        this.history = history;
        this.results = results;
        this.round = round;
    }

    @Override
    public void connect(Listener listener) {
        this.listener = listener;
        this.disconnected = false;
        this.lastPushedLap.set(0);
        this.lastSeenLap.set(0);
        this.staleCount.set(0);
        this.reportedRetired.clear();
        this.corrected = false;
        this.prevLapDrivers = java.util.Set.of();
        listener.onStatus("📡 正在连接 Jolpica 逐圈数据(第 " + round + " 站)…");
        scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void disconnect() {
        disconnected = true;
        scheduler.shutdownNow();
        listener = null;
    }

    @Override
    public String describe() {
        int seen = lastSeenLap.get();
        return "真实近实时 · 第 " + round + " 站" + (seen > 0 ? " · 数据到第 " + seen + " 圈" : "");
    }

    private void poll() {
        if (disconnected || listener == null) {
            return;
        }
        final List<LapSample> all;
        try {
            all = history.laps(round);
        } catch (DataException e) {
            listener.onStatus("⏳ 逐圈数据暂不可用(" + e.getMessage() + "),8 秒后自动重试…");
            return;
        } catch (RuntimeException e) {
            listener.onStatus("⚠️ 拉取失败(" + e.getClass().getSimpleName() + ":" + e.getMessage() + "),自动重试…");
            return;
        }

        int maxLap = maxLap(all);
        lastSeenLap.set(maxLap);
        if (maxLap <= lastPushedLap.get()) {
            staleCount.incrementAndGet();
            if (maxLap == 0 && staleCount.get() >= 2) {
                listener.onStatus("🛰️ 尚无圈速数据 —— 比赛未开始或数据源尚未发布本轮赛程");
            } else if (maxLap > 0 && staleCount.get() >= NO_PROGRESS_LIMIT) {
                listener.onStatus("🏁 数据已静止(第 " + maxLap + " 圈后无更新)—— 比赛结束或轮询已到尽头,可切换回放查看");
                correctAgainstOfficial(); // 官方成绩核对:撤销收官误标(被套圈完赛者)
            }
            return;
        }

        // 新圈出现:取它覆盖的完整一圈,整理为该圈结束时的名次快照(顺序即排位)
        List<PositionUpdate> batch = new ArrayList<>();
        java.util.Set<String> thisLapDrivers = new java.util.HashSet<>();
        for (LapSample s : all) {
            if (s.lap() == maxLap) {
                batch.add(new PositionUpdate(s.driverId(), s.position(), maxLap,
                        s.time(), System.currentTimeMillis()));
                thisLapDrivers.add(s.driverId());
            }
        }
        // 上一圈在跑、这一圈缺席 → 车手退赛,由数据侧明示上报
        if (lastPushedLap.get() > 0) {
            for (String id : prevLapDrivers) {
                if (!thisLapDrivers.contains(id)) {
                    listener.onRetirement(id, maxLap);
                    reportedRetired.add(id);
                }
            }
        }
        prevLapDrivers = java.util.Set.copyOf(thisLapDrivers);
        lastPushedLap.set(maxLap);
        staleCount.set(0);
        listener.onStatus("🔄 第 " + maxLap + " 圈数据已更新(" + batch.size() + " 位车手)");
        if (!batch.isEmpty()) {
            listener.onUpdates(batch);
        }
    }

    private static int maxLap(List<LapSample> all) {
        int m = 0;
        for (LapSample s : all) {
            if (s.lap() > m) {
                m = s.lap();
            }
        }
        return m;
    }

    /**
     * 收官核对(只做一次):拉官方成绩,凡"已上报退赛"而官方确认完赛者
     * (收官圈被套圈冲线,缺最后一圈记录) → 撤销退赛标记。
     * 成绩未发布/拉取失败则保持原判定(下不重试,维持现状)。
     */
    private void correctAgainstOfficial() {
        if (corrected || reportedRetired.isEmpty() || listener == null) {
            return;
        }
        corrected = true;
        try {
            Set<String> finished = new HashSet<>();
            for (FinishingPosition fp : results.raceResults(round)) {
                if (isFinishedStatus(fp.status())) {
                    finished.add(fp.driver().id());
                }
            }
            List<String> rescued = new ArrayList<>();
            for (String id : reportedRetired) {
                if (finished.contains(id)) {
                    rescued.add(id);
                    listener.onRetirementRescinded(id);
                }
            }
            reportedRetired.removeAll(rescued);
            if (!rescued.isEmpty()) {
                listener.onStatus("✅ 官方成绩确认:被套圈完赛的 " + String.join("、", rescued)
                        + " 已移回完赛名单(非退赛)");
            }
        } catch (RuntimeException e) {
            // 官方成绩还没发布:保留实时判定,回放最近一站可看官方确认版
        }
    }

    /** 完赛状态:Finished / Lapped(套圈冲线)/ "+N Lap";其余(Retired 等)未完赛 */
    private static boolean isFinishedStatus(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String s = status.trim();
        return s.equalsIgnoreCase("Finished") || s.equalsIgnoreCase("Lapped")
                || s.startsWith("+");
    }
}
