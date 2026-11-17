package f1.data.mock;

import f1.domain.model.Driver;
import f1.domain.model.PositionUpdate;
import f1.domain.port.LiveTimingProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mock 实时比赛引擎:内置 2026 真实阵容,比赛 53 圈。
 * 模拟一场"标准大奖赛":每帧 0-2 次相邻超车,2 帧一圈(约 75 秒/场);
 * 每局随机抽 1-2 位车手在中后段"剧本退赛"(数据侧明示上报,
 * 用于演示退赛沉底与灰显),其余车手完赛。
 * 纯工作线程驱动(单线程 daemon),不碰 UI —— 由 UI 层负责合流上平台线程。
 */
public final class MockRaceSimulator implements LiveTimingProvider {

    /** 演示节奏:2 帧一圈 → 53 圈约 75 秒 */
    private static final long FRAME_MS = 700;
    private static final int FRAMES_PER_LAP = 2;
    private static final int TOTAL_LAPS = 53;

    private final List<Driver> grid;
    /** 按名次顺序的当前排名(order[0..orderLen) = 仍在赛中的车);初始即发车格顺序 */
    private final int[] order;
    private int orderLen;
    /** 每人一条圈速基准带(秒):发车格越靠后略慢,圈间小幅抖动 */
    private final double[] pace;
    /** 剧本退赛:gridIdx -> 退赛圈(以该圈开头为准),-1 = 不退赛 */
    private final int[] retireAt;
    /** 引擎线程:connect 时重建(disconnect 已 shutdown,复用会抛异常) */
    private ScheduledExecutorService scheduler;

    private volatile Listener listener;
    private volatile int lap;
    private volatile int frame;
    private volatile boolean finished;

    public MockRaceSimulator(List<Driver> grid) {
        if (grid.size() < 2) {
            throw new IllegalArgumentException("模拟器需要至少 2 位车手");
        }
        this.grid = List.copyOf(grid);
        this.order = new int[grid.size()];
        this.orderLen = grid.size();
        this.pace = new double[grid.size()];
        this.retireAt = new int[grid.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            pace[i] = 73.5 + i * 0.28; // 发车格 1 号位最快,末位最慢
            retireAt[i] = -1;
        }
    }

    private ScheduledExecutorService freshScheduler() {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "f1-mock-race");
            t.setDaemon(true);
            return t;
        });
        scheduler = s;
        return s;
    }

    @Override
    public void connect(Listener listener) {
        this.listener = listener;
        this.finished = false;
        this.frame = 0;
        for (int i = 0; i < order.length; i++) {
            order[i] = i; // idempotent 复位(可能上次跑到一半被 disconnect)
            retireAt[i] = -1;
        }
        orderLen = order.length;
        // 随机剧本:抽 1-2 位退赛者(发车格靠后的更"脆"),退赛圈分布在中后段
        int victims = 1 + (int) (Math.random() * 2);
        for (int v = 0; v < victims; v++) {
            int idx = (int) (Math.random() * order.length);
            retireAt[idx] = 18 + (int) (Math.random() * 30); // 18..47 圈
        }
        lap = 1;
        listener.onStatus("🏁 发车!演示比赛开始,发车格按积分榜顺序(全长 " + TOTAL_LAPS
                + " 圈" + (victims > 0 ? ",含 " + victims + " 位随机退赛剧本" : "") + ")");
        pushFullSnapshot("第 1 圈进行中");
        freshScheduler().scheduleAtFixedRate(this::tick, FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void disconnect() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        listener = null;
    }

    @Override
    public String describe() {
        return "Mock 演示 · 第 " + Math.min(lap, TOTAL_LAPS) + "/" + TOTAL_LAPS + " 圈"
                + (finished ? "(已结束)" : "");
    }

    /** 周期心跳:偶尔超车 + 按帧推进圈数 */
    private void tick() {
        if (listener == null || finished) {
            return;
        }
        frame++;
        if (frame % FRAMES_PER_LAP == 0) {
            lap++;
            if (lap > TOTAL_LAPS) {
                finishRace();
                return;
            }
            listener.onStatus("第 " + lap + " 圈开始");
            fireScriptRetirements();
        }
        // 随机 0-2 次相邻超车(仅在仍在赛中的车手之间)
        List<PositionUpdate> batch = new ArrayList<>(4);
        int swaps = (int) (Math.random() * 3);
        for (int s = 0; s < swaps; s++) {
            int j = (int) (Math.random() * Math.max(1, orderLen - 1));
            if (Math.random() < 0.55) {
                int a = order[j], b = order[j + 1];
                order[j] = b;
                order[j + 1] = a;
                batch.add(update(a, j + 1, lap));
                batch.add(update(b, j, lap));
            }
        }
        if (!batch.isEmpty()) {
            listener.onUpdates(batch);
        } else if (frame % FRAMES_PER_LAP == 1) {
            // 该帧无变动 → 静默,由整圈心跳保证新鲜度
        }
    }

    /** 剧本退赛:圈数推进到目标圈时,数据侧明示上报并从赛场移除 */
    private void fireScriptRetirements() {
        boolean removed = false;
        for (int idx = 0; idx < retireAt.length; idx++) {
            if (retireAt[idx] == lap) {
                retireAt[idx] = -1;
                removeFromOrder(idx);
                removed = true;
                if (listener != null) {
                    listener.onRetirement(grid.get(idx).id(), lap);
                }
            }
        }
        if (removed) {
            pushFullSnapshot(null); // 空缺后的车手依次前移,位置全体校正
        }
    }

    /** 把 gridIdx 车手移出比赛序列(位置后车依次前移一位) */
    private void removeFromOrder(int gridIdx) {
        for (int i = 0; i < orderLen; i++) {
            if (order[i] == gridIdx) {
                System.arraycopy(order, i + 1, order, i, orderLen - i - 1);
                orderLen--;
                return;
            }
        }
    }

    private void pushFullSnapshot(String status) {
        if (listener == null) {
            return;
        }
        List<PositionUpdate> all = new ArrayList<>(orderLen);
        for (int i = 0; i < orderLen; i++) {
            all.add(update(order[i], i + 1, lap));
        }
        if (status != null) {
            listener.onStatus(status);
        }
        listener.onUpdates(all);
    }

    private PositionUpdate update(int gridIdx, int position, int thisLap) {
        Driver d = grid.get(gridIdx);
        // 圈速 = 个人基准 + 每圈小幅抖动(±0.4s)
        double secs = pace[gridIdx] + (Math.random() - 0.5) * 0.8;
        String time = formatLapTime(secs);
        return new PositionUpdate(d.id(), position, thisLap, time, System.currentTimeMillis());
    }

    private void finishRace() {
        finished = true;
        if (listener != null) {
            pushFullSnapshot("🏁 演示比赛结束,共 " + TOTAL_LAPS + " 圈(纯演示数据,不代表真实赛果)");
        }
    }

    private static String formatLapTime(double seconds) {
        int s = (int) seconds;
        int ms = (int) Math.round((seconds - s) * 1000);
        return String.format("%d:%02d.%03d", s / 60, s % 60, ms);
    }
}
