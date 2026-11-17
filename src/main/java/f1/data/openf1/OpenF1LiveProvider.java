package f1.data.openf1;

import f1.data.openf1.OpenF1Dtos.DriverDto;
import f1.data.openf1.OpenF1Dtos.LapDto;
import f1.data.openf1.OpenF1Dtos.PositionDto;
import f1.data.openf1.OpenF1Dtos.SessionDto;
import f1.domain.DataException;
import f1.domain.model.Driver;
import f1.domain.model.PositionUpdate;
import f1.domain.port.LiveTimingProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenF1 全场次实时/准实时数据流 —— 覆盖全部五种场次:
 *   1. 练习赛 (FP1 / FP2 / FP3)
 *   2. 冲刺排位赛 (Sprint Qualifying)
 *   3. 冲刺赛 (Sprint)
 *   4. 排位赛 (Qualifying)
 *   5. 正赛 (Race)
 *
 * 工作机制:
 *   - 首次连接自动定位当前正在进行的场次(若当前无直播,回退到最近一场已开始的场次);
 *   - 拉取一次 /v1/drivers 建立车号 -> Driver 映射,并通过 driverSink 通知会话层注册替补/新秀车手;
 *   - 每 5 秒轮询一次 /v1/position(增量)与 /v1/laps(增量)—— 每分钟仅 24 次请求,
 *     同时满足免费档(30 次/分)与 Sponsor 付费档(60 次/分)的限流要求;
 *   - 练习赛/排位赛按最快圈或官方实时名次展示,且绝不误判"在维修区等待"的车手为退赛;
 *   - 正赛/冲刺赛按实时赛道位置展示最新单圈用时與最佳圈速。
 */
public final class OpenF1LiveProvider implements LiveTimingProvider {

    /** 5 秒一轮(2 个端点 = 24 次/分,安全低于免费档 30/min 与付费档 60/min) */
    private static final long POLL_INTERVAL_MS = 5000L;

    private final OpenF1Client client;
    private final List<Driver> knownRoster;
    private final Consumer<Driver> driverRegistrar;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "f1-openf1-live");
                t.setDaemon(true);
                return t;
            });

    private volatile Listener listener;
    private volatile boolean disconnected;
    private volatile SessionDto activeSession;
    private volatile int maxLapSeen;

    /** driver_number -> 领域车手 ID */
    private final Map<Integer, String> numToDriverId = new HashMap<>();
    /** driver_number -> 最新名次 */
    private final Map<Integer, Integer> latestPos = new HashMap<>();
    /** driver_number -> 最新完成的圈号 */
    private final Map<Integer, Integer> latestLap = new HashMap<>();
    /** driver_number -> 最新一圈成绩(秒) */
    private final Map<Integer, Double> lastLapSec = new HashMap<>();
    /** driver_number -> 本场个人最快圈成绩(秒) */
    private final Map<Integer, Double> bestLapSec = new HashMap<>();

    private String lastPositionDate;
    private boolean sessionAnnounced;

    public OpenF1LiveProvider(OpenF1Client client, List<Driver> knownRoster,
                              Consumer<Driver> driverRegistrar) {
        this.client = client;
        this.knownRoster = knownRoster == null ? List.of() : List.copyOf(knownRoster);
        this.driverRegistrar = driverRegistrar;
    }

    @Override
    public void connect(Listener listener) {
        this.listener = listener;
        this.disconnected = false;
        this.activeSession = null;
        this.maxLapSeen = 0;
        this.lastPositionDate = null;
        this.sessionAnnounced = false;
        this.numToDriverId.clear();
        this.latestPos.clear();
        this.latestLap.clear();
        this.lastLapSec.clear();
        this.bestLapSec.clear();

        listener.onStatus("🛰️ 正在连接 OpenF1 实时源(自动识别练习/排位/冲刺/正赛)…");
        scheduler.scheduleAtFixedRate(this::pollSafe, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void disconnect() {
        disconnected = true;
        scheduler.shutdownNow();
        listener = null;
    }

    @Override
    public String describe() {
        SessionDto s = activeSession;
        if (s == null) {
            return "OpenF1 实时流 · 正在定位场次…";
        }
        String country = s.countryName() == null ? "" : s.countryName() + " · ";
        String name = OpenF1Client.translateSessionName(s.sessionName());
        boolean live = OpenF1Client.isCurrentlyLive(s);
        String mode = live ? "🟢 直播中" : "📼 最近场次快照";
        String lapInfo = maxLapSeen > 0 ? " · 第 " + maxLapSeen + " 圈" : "";
        return "OpenF1 · " + country + name + " (" + mode + ")" + lapInfo;
    }

    private void pollSafe() {
        if (disconnected || listener == null) {
            return;
        }
        try {
            pollOnce();
        } catch (DataException e) {
            if (!disconnected && listener != null) {
                listener.onStatus("⚠️ " + e.getMessage());
            }
        } catch (RuntimeException e) {
            if (!disconnected && listener != null) {
                listener.onStatus("⚠️ OpenF1 拉取异常(" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + "),5 秒后自动重试…");
            }
        }
    }

    private void pollOnce() {
        if (activeSession == null) {
            SessionDto target = client.findTargetSession(true);
            if (target == null || target.sessionKey() == null) {
                listener.onStatus("⚠️ OpenF1 暂未找到可用场次,5 秒后重试…");
                return;
            }
            activeSession = target;
            loadSessionDrivers(target.sessionKey());
        }

        int sessionKey = activeSession.sessionKey();
        boolean timedMode = OpenF1Client.isTimedSession(activeSession);

        // 1. 拉取位置变动(首次全量,后续只拉 date > lastPositionDate 的增量)
        List<PositionDto> positions = client.positions(sessionKey, lastPositionDate);
        for (PositionDto p : positions) {
            if (p.driverNumber() != null && p.position() != null && p.position() > 0) {
                latestPos.put(p.driverNumber(), p.position());
            }
            if (p.date() != null && (lastPositionDate == null || p.date().compareTo(lastPositionDate) > 0)) {
                lastPositionDate = p.date();
            }
        }

        // 2. 拉取圈速记录(首次全量,后续只拉最近两圈及之后的增量)
        int minLapQuery = Math.max(1, maxLapSeen - 1);
        List<LapDto> laps = client.laps(sessionKey, lastPositionDate == null ? 0 : minLapQuery);
        for (LapDto lap : laps) {
            if (lap.driverNumber() == null) {
                continue;
            }
            int num = lap.driverNumber();
            int lapNo = lap.lapNumber() == null ? 0 : lap.lapNumber();
            if (lapNo > maxLapSeen) {
                maxLapSeen = lapNo;
            }
            if (lapNo > latestLap.getOrDefault(num, 0)) {
                latestLap.put(num, lapNo);
            }
            if (lap.lapDuration() != null && lap.lapDuration() > 0) {
                lastLapSec.put(num, lap.lapDuration());
                double prevBest = bestLapSec.getOrDefault(num, Double.MAX_VALUE);
                if (lap.lapDuration() < prevBest) {
                    bestLapSec.put(num, lap.lapDuration());
                }
            }
        }

        // 3. 若 /v1/position 尚未返回名次(部分练习赛初期),按最快圈自动排定名次
        if (latestPos.isEmpty() && !bestLapSec.isEmpty()) {
            List<Map.Entry<Integer, Double>> byBest = new ArrayList<>(bestLapSec.entrySet());
            byBest.sort(Map.Entry.comparingByValue());
            int rank = 1;
            for (var e : byBest) {
                latestPos.put(e.getKey(), rank++);
            }
        }

        if (latestPos.isEmpty() && bestLapSec.isEmpty()) {
            String title = (activeSession.countryName() == null ? "" : activeSession.countryName() + " · ")
                    + OpenF1Client.translateSessionName(activeSession.sessionName());
            listener.onStatus("🛰️ 已连接 " + title + " —— 等待赛场发出首条计时数据…");
            return;
        }

        // 4. 组装全体车手最新位置与圈速快照
        List<Integer> allNums = new ArrayList<>(latestPos.keySet());
        allNums.sort(Comparator.comparingInt(n -> latestPos.getOrDefault(n, 99)));

        long nowMs = System.currentTimeMillis();
        List<PositionUpdate> batch = new ArrayList<>(allNums.size());
        for (Integer num : allNums) {
            int pos = latestPos.getOrDefault(num, 99);
            int lapNo = latestLap.getOrDefault(num, 0);
            String driverId = resolveDriverId(num);
            String timeText = formatLapDisplay(num, timedMode);
            batch.add(new PositionUpdate(driverId, pos, lapNo, timeText, nowMs));
        }

        if (!batch.isEmpty() && listener != null && !disconnected) {
            listener.onUpdates(batch);
            if (!sessionAnnounced) {
                sessionAnnounced = true;
                boolean live = OpenF1Client.isCurrentlyLive(activeSession);
                String country = activeSession.countryName() == null ? "" : activeSession.countryName() + " · ";
                String sName = OpenF1Client.translateSessionName(activeSession.sessionName());
                listener.onStatus((live ? "🟢 OpenF1 正在直播: " : "📡 OpenF1 已加载最近场次: ")
                        + country + sName
                        + (timedMode ? " (计时模式:显示最快圈)" : " (正赛/冲刺模式:每 5 秒刷新)"));
            }
        }
    }

    /**
     * 练习赛/排位赛优先展示个人最快圈;正赛/冲刺赛展示上一圈用时(若无则回退最快圈)。
     */
    private String formatLapDisplay(int driverNum, boolean timedMode) {
        Double best = bestLapSec.get(driverNum);
        Double last = lastLapSec.get(driverNum);
        if (timedMode) {
            if (best != null) {
                return formatSeconds(best);
            }
            return last != null ? formatSeconds(last) : "—";
        }
        if (last != null) {
            return formatSeconds(last);
        }
        return best != null ? formatSeconds(best) : "—";
    }

    /** 91.234 → "1:31.234";58.400 → "0:58.400" */
    public static String formatSeconds(double totalSeconds) {
        if (totalSeconds <= 0 || Double.isNaN(totalSeconds) || Double.isInfinite(totalSeconds)) {
            return "—";
        }
        long totalMillis = Math.round(totalSeconds * 1000.0);
        long minutes = totalMillis / 60_000L;
        long remMillis = totalMillis % 60_000L;
        long secs = remMillis / 1000L;
        long ms = remMillis % 1000L;
        return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, secs, ms);
    }

    private void loadSessionDrivers(int sessionKey) {
        // 先用本地花名册按车号打底
        for (Driver d : knownRoster) {
            if (d.number() != null) {
                numToDriverId.put(d.number(), d.id());
            }
        }
        try {
            List<DriverDto> dtos = client.drivers(sessionKey);
            for (DriverDto dto : dtos) {
                if (dto.driverNumber() == null) {
                    continue;
                }
                int num = dto.driverNumber();
                Driver matched = findInKnownRoster(num, dto.nameAcronym());
                if (matched != null) {
                    numToDriverId.put(num, matched.id());
                } else {
                    String id = dto.nameAcronym() != null && !dto.nameAcronym().isBlank()
                            ? dto.nameAcronym().toLowerCase(Locale.ROOT)
                            : "driver_" + num;
                    String code = dto.nameAcronym() != null && !dto.nameAcronym().isBlank()
                            ? dto.nameAcronym().toUpperCase(Locale.ROOT)
                            : "#" + num;
                    String given = dto.firstName() != null ? dto.firstName() : code;
                    String family = dto.lastName() != null ? dto.lastName() : "";
                    String team = dto.teamName() != null ? dto.teamName() : "—";
                    Driver dynamic = new Driver(id, code, num, given, family, "", null, id, team);
                    numToDriverId.put(num, id);
                    if (driverRegistrar != null) {
                        driverRegistrar.accept(dynamic);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 车手详情拉取失败不阻断位置流,已有本地花名册兜底
        }
    }

    private Driver findInKnownRoster(int number, String acronym) {
        for (Driver d : knownRoster) {
            if (d.number() != null && d.number() == number) {
                return d;
            }
        }
        if (acronym != null && !acronym.isBlank()) {
            for (Driver d : knownRoster) {
                if (acronym.equalsIgnoreCase(d.code())) {
                    return d;
                }
            }
        }
        return null;
    }

    private String resolveDriverId(int driverNum) {
        String id = numToDriverId.get(driverNum);
        if (id != null) {
            return id;
        }
        Driver matched = findInKnownRoster(driverNum, null);
        if (matched != null) {
            numToDriverId.put(driverNum, matched.id());
            return matched.id();
        }
        String fallback = "car_" + driverNum;
        numToDriverId.put(driverNum, fallback);
        return fallback;
    }
}
