package f1.data.mock;

import f1.domain.model.FinishingPosition;
import f1.domain.model.LapSample;
import f1.domain.model.PositionUpdate;
import f1.domain.port.LapHistoryProvider;
import f1.domain.port.LivePlayback;
import f1.domain.port.LiveTimingProvider;
import f1.domain.port.SeasonProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 历史回放引擎:把某已完赛站的真实逐圈记录当作"实况"重放。
 * connect 后先在工作线程加载 /laps.json(此时发状态消息),
 * 再按每圈节奏推进:每圈 = 一批 PositionUpdate(该圈全部车手,按名次排序)。
 *
 * 支持运行中变速(LivePlayback.setSpeed):1× 基准 = 1000ms/圈,
 * 全站 53 圈约 53 秒;0.5× ≈ 1 分 46 秒,4× 约 13 秒。
 *
 * 退赛判定以"官方成绩"为准:被套圈车手冲线后同样不再有新的逐圈记录,
 * 只有官方标记为未完赛(Retired/Accident/…)的缺席者才算退赛 ——
 * 否则回放会把"少跑 N 圈的完赛者"误报成退赛(见 {@link #isFinishedStatus})。
 *
 * 与数据源彻底解耦的同一接口,UI 完全无感知 —— 实时页"演示/回放/真实"三态共用。
 */
public final class ReplayRaceEngine implements LivePlayback {

    /** 1× 倍速时每圈间隔 */
    private static final long BASE_LAP_MS = 1000;

    private final LapHistoryProvider history;
    /** 官方成绩源(组合根注入的"恒真实"实现;仅用它裁决谁真的退赛) */
    private final SeasonProvider results;
    private final int round;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "f1-replay");
                t.setDaemon(true);
                return t;
            });

    private volatile Listener listener;
    /** byLap.get(i) = 第 (i+1) 圈全体车手样本(按名次升序) */
    private volatile List<List<LapSample>> byLap;
    private volatile int cursor;
    private volatile boolean finished;
    private volatile boolean disconnected;
    private volatile double rate = 1.0;
    private ScheduledFuture<?> timer;
    /** 退赛清单:每个元素 {driverId, atLap}——推进到 atLap 圈批次时上报 */
    private volatile List<String[]> retirements = List.of();

    public ReplayRaceEngine(LapHistoryProvider history, SeasonProvider results, int round) {
        this.history = history;
        this.results = results;
        this.round = round;
    }

    @Override
    public void connect(Listener listener) {
        this.listener = listener;
        this.disconnected = false;
        this.finished = false;
        this.cursor = 0;
        this.byLap = null;
        // 注意:不在 connect 里重置 rate —— attach 可能先 setSpeed 再 connect(实例默认 1.0)
        listener.onStatus("⏳ 正在加载第 " + round + " 站逐圈数据…");
        worker.execute(this::loadThenStream);
    }

    /** 播放变速:正数倍速;改变后下一次心跳即按新节奏走(加载中也生效) */
    @Override
    public synchronized void setSpeed(double rate) {
        if (!(rate >= 0.1 && rate <= 20)) {
            throw new IllegalArgumentException("倍速需在 0.1× 到 20× 之间");
        }
        this.rate = rate;
        reschedule();
    }

    private synchronized void reschedule() {
        cancelTimer();
        if (!disconnected && !finished && listener != null && byLap != null && !byLap.isEmpty()) {
            timer = worker.scheduleAtFixedRate(this::step,
                    Math.max(60L, (long) (BASE_LAP_MS / rate)),  // 首拍快一点,变速后无感
                    Math.max(60L, (long) (BASE_LAP_MS / rate)),
                    TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void loadThenStream() {
        if (disconnected || listener == null) {
            return;
        }
        try {
            List<LapSample> all = history.laps(round);
            byLap = groupByLap(all);
            if (byLap.isEmpty()) {
                listener.onStatus("❌ 第 " + round + " 站没有可回放的逐圈记录");
                return;
            }
            retirements = computeRetirements(fetchOfficialDnf());
            int dnf = retirements.size();
            listener.onStatus("🎞️ 回放开始:第 " + round + " 站 · 共 " + byLap.size()
                    + " 圈 · 1× ≈ " + Math.round(byLap.size() * BASE_LAP_MS / 1000.0) + " 秒"
                    + (dnf > 0 ? " · 官方确认 " + dnf + " 人退赛" : ""));
            reschedule();
        } catch (RuntimeException e) {
            if (listener != null) {
                listener.onStatus("❌ 回放加载失败:" + e.getMessage());
            }
        }
    }

    /**
     * 拉官方成绩,取"确认为未完赛"的车手集合。
     * 拉取失败(成绩未发布/网络问题)返回 null —— 调用方将退化为
     * "凡缺席到底者皆判退赛"(旧口径,宁可多标,结果发布后再看即准)。
     */
    private Set<String> fetchOfficialDnf() {
        try {
            Set<String> dnf = new HashSet<>();
            for (FinishingPosition fp : results.raceResults(round)) {
                if (!isFinishedStatus(fp.status())) {
                    dnf.add(fp.driver().id());
                }
            }
            return dnf;
        } catch (RuntimeException e) {
            if (listener != null) {
                listener.onStatus("⚠️ 官方成绩暂不可用(" + e.getMessage()
                        + "),退赛先按逐圈数据推断 —— 结束后以官方名单为准");
            }
            return null;
        }
    }

    /**
     * 判定退赛:某车手"最后出现圈 &lt; 全场最大圈"(此后无记录)且官方确认其未完赛。
     * 判退圈 atLap = 最后出现圈 + 1。
     * 官方名单缺席(null)时退化为纯逐圈推断(见 {@link #fetchOfficialDnf})。
     */
    private List<String[]> computeRetirements(Set<String> officialDnfOrNull) {
        Map<String, Integer> lastSeen = new java.util.HashMap<>();
        int maxLap = 0;
        for (List<LapSample> group : byLap) {
            if (group.isEmpty()) {
                continue;
            }
            int lapNo = group.get(0).lap();
            maxLap = Math.max(maxLap, lapNo);
            for (LapSample s : group) {
                lastSeen.put(s.driverId(), lapNo);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (var e : lastSeen.entrySet()) {
            boolean absentAtEnd = e.getValue() < maxLap;
            boolean officiallyOut = officialDnfOrNull == null || officialDnfOrNull.contains(e.getKey());
            if (absentAtEnd && officiallyOut) {
                out.add(new String[]{e.getKey(), String.valueOf(e.getValue() + 1)});
            }
        }
        return out;
    }

    /**
     * 官方成绩里的"完赛"状态:Finished、Lapped(被套圈但冲线完赛)、
     * 或套圈差格式 "+N Lap"。其余(Retired/Accident/Disqualified/Engine/…)一律未完赛。
     */
    private static boolean isFinishedStatus(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String s = status.trim();
        return s.equalsIgnoreCase("Finished") || s.equalsIgnoreCase("Lapped")
                || s.startsWith("+");
    }

    /** 按圈分组并按名次排序;顺带确认圈号连续(缺失圈直接跳过不报错) */
    private static List<List<LapSample>> groupByLap(List<LapSample> all) {
        List<List<LapSample>> laps = new ArrayList<>();
        int current = 0;
        for (LapSample s : all) {
            if (s.lap() != current) {
                if (s.lap() < current) {
                    continue; // 防御:乱序输入
                }
                laps.add(new ArrayList<>());
                current = s.lap();
            }
            laps.get(laps.size() - 1).add(s);
        }
        for (List<LapSample> lap : laps) {
            lap.sort(Comparator.comparingInt(LapSample::position));
        }
        return laps;
    }

    private void step() {
        if (disconnected || finished || listener == null || byLap == null) {
            return;
        }
        if (cursor >= byLap.size()) {
            finished = true;
            cancelTimer();
            listener.onStatus("🏁 回放结束 —— 以上为第 " + round + " 站真实逐圈实况");
            return;
        }
        List<LapSample> lap = byLap.get(cursor++);
        int lapNo = lap.isEmpty() ? cursor : lap.get(0).lap();
        // 时间轴推进到该圈:先上报"上一圈后未再出圈"的车手(真实退赛,一次一发)
        for (String[] ret : retirements) {
            if (ret[1].equals(String.valueOf(lapNo))) {
                listener.onRetirement(ret[0], lapNo);
            }
        }
        List<PositionUpdate> batch = new ArrayList<>(lap.size());
        for (LapSample s : lap) {
            batch.add(new PositionUpdate(s.driverId(), s.position(), lapNo,
                    s.time(), System.currentTimeMillis()));
        }
        if (cursor < byLap.size()) {
            listener.onStatus("⏱️ 第 " + lapNo + "/" + byLap.size() + " 圈"
                    + (rate > 1.001 || rate < 0.999 ? " · " + trimRate(rate) + "×" : ""));
        }
        listener.onUpdates(batch);
    }

    private static String trimRate(double r) {
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    @Override
    public void disconnect() {
        disconnected = true;
        cancelTimer();
        worker.shutdownNow();
        listener = null;
    }

    @Override
    public String describe() {
        int total = byLap == null ? 0 : byLap.size();
        String base = "回放 · 第 " + round + " 站" + (total > 0 ? " · " + Math.min(cursor + 1, total) + "/" + total + " 圈" : " · 加载中");
        return rate > 1.001 || rate < 0.999 ? base + " · " + trimRate(rate) + "×" : base;
    }
}
