package f1.data.openf1;

import com.fasterxml.jackson.core.type.TypeReference;
import f1.app.AppConfig;
import f1.data.openf1.OpenF1Dtos.DriverDto;
import f1.data.openf1.OpenF1Dtos.LapDto;
import f1.data.openf1.OpenF1Dtos.PositionDto;
import f1.data.openf1.OpenF1Dtos.SessionDto;
import f1.data.openf1.OpenF1Dtos.TokenDto;
import f1.domain.DataException;
import f1.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Comparator;
import java.util.List;

/**
 * OpenF1 REST 客户端 —— 封装认证(Sponsor 付费令牌自动换取与续期)与全部实时场次查询。
 *
 * 支持三种工作方式:
 *   1. 直接 Token / API Key:在设置页填入 openf1Token,请求头带 Authorization: Bearer <token>
 *   2. 账号 + 密码:通过 POST https://api.openf1.org/token 自动换取 Bearer token 并缓存(提前 2 分钟自动续期)
 *   3. 未配置凭据(免费档):不带认证头,非直播时段可查全部历史场次;直播时段遇 401 给出明确中文引导
 */
public final class OpenF1Client {

    private static final String API_BASE = "https://api.openf1.org/v1/";
    private static final String TOKEN_URL = "https://api.openf1.org/token";
    /** OpenF1 定义的实时窗口:场次开始前 30 分钟 ~ 结束后 30 分钟 */
    private static final Duration LIVE_BUFFER_BEFORE = Duration.ofMinutes(30);
    private static final Duration LIVE_BUFFER_AFTER = Duration.ofMinutes(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AppConfig config;

    /** 账号密码换取的临时 token 缓存 */
    private volatile String cachedToken;
    private volatile long tokenExpiresAtMs;

    public OpenF1Client(AppConfig config) {
        this.config = config;
    }

    /** 设置页修改凭据后清空旧 token,下次请求立即按新凭据重取 */
    public synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiresAtMs = 0L;
    }

    /**
     * 解析当前可用的 Bearer token;未配置凭据时返回 null(走免费档)。
     */
    public synchronized String resolveBearerToken() {
        if (config.openf1Token != null && !config.openf1Token.isBlank()) {
            String raw = config.openf1Token.trim();
            if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                raw = raw.substring(7).trim();
            }
            return raw;
        }
        String user = config.openf1Username == null ? "" : config.openf1Username.trim();
        String pass = config.openf1Password == null ? "" : config.openf1Password;
        if (user.isEmpty() || pass.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAtMs) {
            return cachedToken;
        }
        String form = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "f1-dashboard/1.0 (JavaFX desktop)")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 400) {
                throw new DataException("OpenF1 账号或密码错误(HTTP " + resp.statusCode() + ")");
            }
            if (resp.statusCode() != 200) {
                throw new DataException("OpenF1 令牌获取失败(HTTP " + resp.statusCode() + ")");
            }
            TokenDto dto = Json.mapper().readValue(resp.body(), TokenDto.class);
            if (dto == null || dto.accessToken() == null || dto.accessToken().isBlank()) {
                throw new DataException("OpenF1 返回的 access_token 为空");
            }
            int expiresSec = dto.expiresIn() != null && dto.expiresIn() > 180 ? dto.expiresIn() : 3300;
            cachedToken = dto.accessToken().trim();
            tokenExpiresAtMs = now + (expiresSec - 120) * 1000L;
            return cachedToken;
        } catch (IOException e) {
            throw new DataException("OpenF1 认证网络请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("OpenF1 认证请求被中断", e);
        }
    }

    /**
     * 设置页「测试连接」按钮调用:验证凭据与最新场次可达性,返回可读结论。
     */
    public String testConnection() {
        invalidateToken();
        String token = resolveBearerToken();
        List<SessionDto> latest = getList("sessions?session_key=latest", new TypeReference<>() {});
        String sessionDesc = "未查到场次";
        if (!latest.isEmpty()) {
            SessionDto s = latest.get(latest.size() - 1);
            sessionDesc = (s.countryName() == null ? "" : s.countryName() + " · ")
                    + translateSessionName(s.sessionName())
                    + " (session_key=" + s.sessionKey() + ")";
        }
        if (token != null) {
            return "✓ 已认证连接成功(Sponsor 实时权限已就绪) · 最近场次: " + sessionDesc;
        }
        return "✓ 免费档连接成功(未填凭据,仅非直播时段可用) · 最近场次: " + sessionDesc;
    }

    /**
     * 查找当前正在直播的场次(含练习赛 1/2/3、冲刺排位、冲刺赛、排位赛、正赛);
     * 若当前无直播且 fallbackToLatest=true,则返回最近已开始的一场供查看/验证。
     */
    public SessionDto findTargetSession(boolean fallbackToLatest) {
        try {
            List<SessionDto> latestList = getList("sessions?session_key=latest", new TypeReference<>() {});
            if (!latestList.isEmpty()) {
                SessionDto s = latestList.get(latestList.size() - 1);
                if (isCurrentlyLive(s) || fallbackToLatest) {
                    return s;
                }
            }
        } catch (DataException e) {
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                throw e; // 直播锁定期的 401 直接上抛,提示用户配置凭据
            }
        }

        int year = Year.now().getValue();
        List<SessionDto> all = getList("sessions?year=" + year, new TypeReference<>() {});
        Instant now = Instant.now();
        // 1. 优先找正在直播窗口内的场次
        for (SessionDto s : all) {
            if (Boolean.TRUE.equals(s.isCancelled())) {
                continue;
            }
            if (isCurrentlyLive(s)) {
                return s;
            }
        }
        if (!fallbackToLatest) {
            return null;
        }
        // 2. 回退到最近一场已开始的场次
        return all.stream()
                .filter(s -> !Boolean.TRUE.equals(s.isCancelled()))
                .filter(s -> {
                    Instant start = parseInstant(s.dateStart());
                    return start != null && !start.isAfter(now);
                })
                .max(Comparator.comparing(s -> parseInstant(s.dateStart())))
                .orElse(all.isEmpty() ? null : all.get(all.size() - 1));
    }

    public List<DriverDto> drivers(int sessionKey) {
        return getList("drivers?session_key=" + sessionKey, new TypeReference<>() {});
    }

    /** 拉取名次记录;afterDateIso 非空时只拉增量(date>afterDateIso) */
    public List<PositionDto> positions(int sessionKey, String afterDateIso) {
        String path = "position?session_key=" + sessionKey;
        if (afterDateIso != null && !afterDateIso.isBlank()) {
            path += "&date%3E" + URLEncoder.encode(afterDateIso, StandardCharsets.UTF_8);
        }
        return getList(path, new TypeReference<>() {});
    }

    /** 拉取圈速记录;minLap > 0 时只拉 >= minLap 的圈,降低单次响应体积 */
    public List<LapDto> laps(int sessionKey, int minLap) {
        String path = "laps?session_key=" + sessionKey;
        if (minLap > 1) {
            path += "&lap_number%3E%3D" + minLap;
        }
        return getList(path, new TypeReference<>() {});
    }

    public static boolean isCurrentlyLive(SessionDto s) {
        if (s == null || Boolean.TRUE.equals(s.isCancelled())) {
            return false;
        }
        Instant start = parseInstant(s.dateStart());
        Instant end = parseInstant(s.dateEnd());
        if (start == null) {
            return false;
        }
        if (end == null) {
            end = start.plus(Duration.ofHours(3));
        }
        Instant now = Instant.now();
        return !now.isBefore(start.minus(LIVE_BUFFER_BEFORE))
                && !now.isAfter(end.plus(LIVE_BUFFER_AFTER));
    }

    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 把 OpenF1 的英文场次名转成直观中文 */
    public static String translateSessionName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知场次";
        }
        return switch (raw.trim()) {
            case "Practice 1" -> "一练 (FP1)";
            case "Practice 2" -> "二练 (FP2)";
            case "Practice 3" -> "三练 (FP3)";
            case "Sprint Qualifying", "Sprint Shootout" -> "冲刺排位赛 (SQ)";
            case "Sprint" -> "冲刺赛 (Sprint)";
            case "Qualifying" -> "排位赛 (Qualifying)";
            case "Race" -> "正赛 (Race)";
            default -> raw;
        };
    }

    /** 是否为计时排位/练习类场次(按最快圈排名、无退赛概念) */
    public static boolean isTimedSession(SessionDto s) {
        if (s == null) {
            return false;
        }
        String type = s.sessionType() == null ? "" : s.sessionType();
        String name = s.sessionName() == null ? "" : s.sessionName();
        return "Practice".equalsIgnoreCase(type)
                || "Qualifying".equalsIgnoreCase(type)
                || name.toLowerCase().contains("practice")
                || name.toLowerCase().contains("qualifying");
    }

    private <T> List<T> getList(String relativeUrl, TypeReference<List<T>> type) {
        String token = resolveBearerToken();
        HttpResponse<String> resp = sendGet(API_BASE + relativeUrl, token);
        // 若 token 刚过期导致 401,且配置的是账号密码,清缓存重试一次
        if (resp.statusCode() == 401 && token != null
                && (config.openf1Token == null || config.openf1Token.isBlank())) {
            invalidateToken();
            token = resolveBearerToken();
            resp = sendGet(API_BASE + relativeUrl, token);
        }
        if (resp.statusCode() == 401) {
            throw new DataException(token == null
                    ? "🔒 当前正处于 F1 直播时段,OpenF1 已锁定免费访问(401)—— 请在「⚙️ 设置」页填入 OpenF1 Sponsor 账号或 Token"
                    : "🔒 OpenF1 认证被拒(401)—— 请在「⚙️ 设置」页检查 OpenF1 账号/密码或 Token 是否正确");
        }
        if (resp.statusCode() == 429) {
            throw new DataException("OpenF1 请求过于频繁(429),稍后自动恢复");
        }
        if (resp.statusCode() != 200) {
            throw new DataException("OpenF1 返回异常 HTTP " + resp.statusCode());
        }
        try {
            List<T> parsed = Json.mapper().readValue(resp.body(), type);
            return parsed == null ? List.of() : parsed;
        } catch (IOException e) {
            throw new DataException("OpenF1 数据解析失败: " + e.getMessage(), e);
        }
    }

    private static HttpResponse<String> sendGet(String url, String bearerToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "f1-dashboard/1.0 (JavaFX desktop)")
                .header("Accept", "application/json")
                .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        try {
            return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DataException("OpenF1 网络请求失败(请检查网络): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("OpenF1 请求被中断", e);
        }
    }
}
