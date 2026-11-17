package f1.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import f1.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用配置(持久化在 ~/.f1-dashboard/config.json)。
 * 默认真实数据源;文件缺失/损坏时回退默认值,不抛错。
 */
public final class AppConfig {

    public enum Source {
        /** 真实数据(Jolpica 积分/赛历 + RSS 新闻) */
        REAL,
        /** Mock 演示数据(离线可用,实时页演示用) */
        MOCK
    }

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".f1-dashboard", "config.json");
    private static final ObjectMapper JSON = Json.mapper();

    public Source source = Source.REAL;

    /** 桌面通知总开关(设置页可改并持久化);旧配置无此字段时保持默认开启 */
    public boolean notifyEnabled = true;

    /**
     * OpenF1 付费订阅(Sponsor)凭据:
     * - openf1Username + openf1Password:通过 https://api.openf1.org/token 换取 Bearer token
     * - openf1Token:若直接粘贴 Bearer token / API key 则优先使用
     */
    public String openf1Username = "";
    public String openf1Password = "";
    public String openf1Token = "";

    public static AppConfig load() {
        try {
            if (Files.exists(FILE)) {
                AppConfig cfg = JSON.readValue(Files.readString(FILE), AppConfig.class);
                if (cfg.source == null) {
                    cfg.source = Source.REAL;
                }
                if (cfg.openf1Username == null) {
                    cfg.openf1Username = "";
                }
                if (cfg.openf1Password == null) {
                    cfg.openf1Password = "";
                }
                if (cfg.openf1Token == null) {
                    cfg.openf1Token = "";
                }
                return cfg;
            }
        } catch (IOException e) {
            System.err.println("配置读取失败,使用默认值: " + e.getMessage());
        }
        return new AppConfig();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, Json.write(this));
        } catch (IOException e) {
            System.err.println("配置保存失败: " + e.getMessage());
        }
    }

    public Source source() {
        return source;
    }

    /** 是否已配置 OpenF1 付费凭据(直接 Token 或 账号+密码) */
    public boolean hasOpenF1Credentials() {
        if (openf1Token != null && !openf1Token.isBlank()) {
            return true;
        }
        return openf1Username != null && !openf1Username.isBlank()
                && openf1Password != null && !openf1Password.isBlank();
    }
}
