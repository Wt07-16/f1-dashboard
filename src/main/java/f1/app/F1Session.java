package f1.app;

import f1.data.jolpica.JolpicaLapHistoryProvider;
import f1.data.jolpica.JolpicaLiveRace;
import f1.data.jolpica.JolpicaSeasonProvider;
import f1.data.jolpica.JolpicaStandingsProvider;
import f1.data.mock.MockRaceSimulator;
import f1.data.mock.MockSeasonProvider;
import f1.data.mock.MockSnapshot;
import f1.data.mock.MockStandingsProvider;
import f1.data.mock.ReplayRaceEngine;
import f1.data.openf1.OpenF1Client;
import f1.data.openf1.OpenF1LiveProvider;
import f1.data.rss.RssNewsProvider;
import f1.domain.model.Driver;
import f1.domain.model.DriverChampRow;
import f1.domain.model.FinishingPosition;
import f1.domain.model.LapSample;
import f1.domain.model.NewsArticle;
import f1.domain.model.RaceEntry;
import f1.domain.model.TeamChampRow;
import f1.domain.port.LapHistoryProvider;
import f1.domain.port.LiveTimingProvider;
import f1.domain.port.NewsProvider;
import f1.domain.port.SeasonProvider;
import f1.domain.port.StandingsProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 会话门面 —— 组合根的一部分。
 * 这是唯一知道"哪个实现对应哪个数据源"的地方:
 * UI 通过它拿到的永远是 port 接口 / 具体流的工厂方法,
 * 数据源在 REAL/MOCK 之间切换只重建这里的内部实现,页面零改动。
 *
 * 实时流(LiveTimingProvider)由页面按需打开,用完即 disconnect;
 * 本类只负责按需创建,不持有长连接。
 */
public final class F1Session {

    /** 单站成绩缓存时效(资料卡轨迹/日历成绩弹窗反复查看时不再打网络) */
    private static final long RESULTS_CACHE_TTL_MS = 30 * 60_000L;

    private final AppConfig config;
    private final ScheduledExecutorService executor;

    // 核心数据源(MOCK = 内嵌快照,仅赛历/积分;新闻始终走网络,离线下会提示错误)
    private SeasonProvider season;
    private StandingsProvider standings;
    private NewsProvider news;
    private final LapHistoryProvider lapHistory = new JolpicaLapHistoryProvider();
    /** 恒真实官方成绩源(不分数据源):回放/近实时引擎用它裁决"谁真的退赛" */
    private final SeasonProvider officialResults = new JolpicaSeasonProvider();
    /** OpenF1 实时/历史客户端(覆盖练习/排位/冲刺/正赛全部五种场次) */
    private final OpenF1Client openf1Client;
    /** 运行期从 OpenF1 动态发现的车手(如一练新秀/替补车手) */
    private final Map<String, Driver> dynamicDrivers = new ConcurrentHashMap<>();

    /** round -> (拉取时刻, 成绩);多线程读写安全 */
    private final Map<Integer, CacheEntry> resultsCache = new ConcurrentHashMap<>();
    private record CacheEntry(long atMillis, List<FinishingPosition> value) {
    }

    /** round -> (拉取时刻, 逐圈数据);圈速图/回放共用,避免切车手重拉分页请求 */
    private final Map<Integer, LapCacheEntry> lapsCache = new ConcurrentHashMap<>();
    private record LapCacheEntry(long atMillis, List<LapSample> value) {
    }

    /** 赛历/积分/新闻的缓存时效:数据几分钟才变一次,不该每进一次页面就重打网络 */
    private static final long SCHEDULE_TTL_MS = 5 * 60_000L;
    private static final long STANDINGS_TTL_MS = 60_000L;
    private static final long NEWS_TTL_MS = 60_000L;

    private record Timed<T>(long atMillis, T value) {
        boolean fresh(long ttlMillis) {
            return System.currentTimeMillis() - atMillis < ttlMillis;
        }
    }

    private volatile Timed<List<RaceEntry>> scheduleCache;
    private final Map<Integer, Timed<List<DriverChampRow>>> driverStandingsCache = new ConcurrentHashMap<>();
    private final Map<Integer, Timed<List<TeamChampRow>>> teamStandingsCache = new ConcurrentHashMap<>();
    private volatile Timed<List<NewsArticle>> newsCache;

    public F1Session(AppConfig config, ScheduledExecutorService executor) {
        this.config = config;
        this.executor = executor;
        this.openf1Client = new OpenF1Client(config);
        rebuildCore();
    }

    /** 切换数据源模式并持久化;只替换核心 Provider,UI 持有的接口引用不变 */
    public synchronized void switchSource(AppConfig.Source source) {
        if (config.source != source) {
            config.source = source;
            rebuildCore();
            config.save();
        }
    }

    private void rebuildCore() {
        boolean mock = config.source == AppConfig.Source.MOCK;
        this.season = mock ? new MockSeasonProvider() : new JolpicaSeasonProvider();
        this.standings = mock ? new MockStandingsProvider() : new JolpicaStandingsProvider();
        this.news = new RssNewsProvider();
        clearResultsCache(); // 换数据源后旧成绩语义不再可靠
        scheduleCache = null;
        driverStandingsCache.clear();
        teamStandingsCache.clear();
        newsCache = null;
    }

    public AppConfig config() {
        return config;
    }

    public ScheduledExecutorService executor() {
        return executor;
    }

    public AppConfig.Source source() {
        return config.source;
    }

    public SeasonProvider season() {
        return season;
    }

    /**
     * 单站正式成绩(带 TTL 缓存,避免资料卡轨迹/成绩弹窗重复打网络)。
     * 未发布或不可用时由实现抛 {@link f1.domain.DataException}。
     */
    public List<FinishingPosition> raceResultsCached(int round) {
        CacheEntry hit = resultsCache.get(round);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMillis() < RESULTS_CACHE_TTL_MS) {
            return hit.value();
        }
        List<FinishingPosition> fresh = season.raceResults(round);
        resultsCache.put(round, new CacheEntry(now, fresh));
        return fresh;
    }

    /** 清空单站成绩缓存(切换数据源时调用,避免旧源的脏数据) */
    public void clearResultsCache() {
        resultsCache.clear();
    }

    /**
     * 某站完整逐圈数据(带 TTL 缓存:一次 ~1000 条、内部多页合并,不该重复拉)。
     * 未发布或不可用时由实现抛 {@link f1.domain.DataException}。
     */
    public List<LapSample> lapsCached(int round) {
        LapCacheEntry hit = lapsCache.get(round);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMillis() < RESULTS_CACHE_TTL_MS) {
            return hit.value();
        }
        List<LapSample> fresh = lapHistory.laps(round);
        lapsCache.put(round, new LapCacheEntry(now, fresh));
        return fresh;
    }

    public StandingsProvider standings() {
        return standings;
    }

    public NewsProvider news() {
        return news;
    }

    public LapHistoryProvider lapHistory() {
        return lapHistory;
    }

    // ---- 实时流工厂(实时页点击按钮时按需创建;实现不泄露到 UI) ----

    /** 🎬 Mock 演示比赛:纯本地模拟,离线可用;阵容 = 2026 真实 23 人 */
    public LiveTimingProvider demoRace() {
        return new MockRaceSimulator(driverRoster());
    }

    /** 🎞️ 回放引擎:某已完赛站的真实逐圈数据,按时间轴重放(退赛按官方成绩裁决) */
    public LiveTimingProvider replayRace(int round) {
        return new ReplayRaceEngine(lapHistory, officialResults, round);
    }

    /** 📡 真实近实时(Jolpica 赛后逐圈兜底) */
    public LiveTimingProvider liveRace(int round) {
        return new JolpicaLiveRace(lapHistory, officialResults, round);
    }

    /**
     * 🛰️ OpenF1 全场次实时流:覆盖练习赛(FP1/2/3)、冲刺排位赛、冲刺赛、排位赛、正赛。
     * 当前有场次直播时接入实时流,无直播时自动加载最近一场场次的真实数据。
     */
    public LiveTimingProvider openf1Live() {
        return new OpenF1LiveProvider(openf1Client, driverRoster(), this::registerDynamicDriver);
    }

    /** 设置页修改 OpenF1 凭据后调用:立即清空缓存 token 并持久化配置 */
    public void updateOpenF1Credentials(String username, String password, String token) {
        config.openf1Username = username == null ? "" : username.trim();
        config.openf1Password = password == null ? "" : password;
        config.openf1Token = token == null ? "" : token.trim();
        config.save();
        openf1Client.invalidateToken();
    }

    /** 设置页「测试连接」按钮调用(在工作线程执行) */
    public String testOpenF1Connection() {
        return openf1Client.testConnection();
    }

    public void registerDynamicDriver(Driver driver) {
        if (driver != null && driver.id() != null) {
            dynamicDrivers.put(driver.id(), driver);
        }
    }

    public Driver findDynamicDriver(String id) {
        return id == null ? null : dynamicDrivers.get(id);
    }

    /** 当前赛季车手花名册(快照层,Mock 引擎/回放取名用;未知 ID 兜底显示原名) */
    public List<Driver> driverRoster() {
        List<Driver> out = new ArrayList<>(MockSnapshot.DRIVER_STANDINGS.size());
        for (var row : MockSnapshot.DRIVER_STANDINGS) {
            out.add(row.driver());
        }
        return out;
    }

    // ---- 带 TTL 的内存缓存:重复进入页面即时显示,过期才重新走网络 ----

    /** 赛历(5 分钟缓存);force=true 跳过缓存(「刷新」按钮用) */
    public List<RaceEntry> scheduleCached(boolean force) {
        Timed<List<RaceEntry>> hit = scheduleCache;
        if (!force && hit != null && hit.fresh(SCHEDULE_TTL_MS)) {
            return hit.value();
        }
        List<RaceEntry> fresh = season.schedule();
        scheduleCache = new Timed<>(System.currentTimeMillis(), fresh);
        return fresh;
    }

    /** 某轮车手积分榜(60 秒缓存) */
    public List<DriverChampRow> driverStandingsCached(int round, boolean force) {
        Timed<List<DriverChampRow>> hit = driverStandingsCache.get(round);
        if (!force && hit != null && hit.fresh(STANDINGS_TTL_MS)) {
            return hit.value();
        }
        List<DriverChampRow> fresh = standings.driverStandings(round);
        driverStandingsCache.put(round, new Timed<>(System.currentTimeMillis(), fresh));
        return fresh;
    }

    /** 某轮车队积分榜(60 秒缓存) */
    public List<TeamChampRow> teamStandingsCached(int round, boolean force) {
        Timed<List<TeamChampRow>> hit = teamStandingsCache.get(round);
        if (!force && hit != null && hit.fresh(STANDINGS_TTL_MS)) {
            return hit.value();
        }
        List<TeamChampRow> fresh = standings.teamStandings(round);
        teamStandingsCache.put(round, new Timed<>(System.currentTimeMillis(), fresh));
        return fresh;
    }

    /** 新闻(60 秒缓存):缓存全量,按 limit 截取,预热与页面共用同一份 */
    public List<NewsArticle> newsCached(int limit, boolean force) {
        Timed<List<NewsArticle>> hit = newsCache;
        List<NewsArticle> all;
        if (!force && hit != null && hit.fresh(NEWS_TTL_MS)) {
            all = hit.value();
        } else {
            all = news.latestNews(Integer.MAX_VALUE);
            newsCache = new Timed<>(System.currentTimeMillis(), all);
        }
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /**
     * 启动后在后台预热:先把赛历、最新积分榜、新闻拉进缓存,
     * 用户第一次点进「积分榜」「新闻」时通常已经有数据了。
     * 失败一律静默 —— 页面仍会按需重试。
     */
    public void warmUp() {
        try {
            List<RaceEntry> races = scheduleCached(false);
            int latest = 0;
            for (RaceEntry r : races) {
                if (r.completed()) {
                    latest = Math.max(latest, r.round());
                }
            }
            if (latest > 0) {
                driverStandingsCached(latest, false);
                teamStandingsCached(latest, false);
            }
        } catch (Throwable ignored) {
            // 预热失败不影响使用
        }
        try {
            newsCached(1, false); // 缓存的是全量,limit 只决定返回多少
        } catch (Throwable ignored) {
            // 预热失败不影响使用
        }
    }

    /** 应用退出时调用,释放数据源资源 */
    public void close() {
        // 实时流由页面生命周期管理(onHidden 即 disconnect);退出时无可持有连接
    }
}
