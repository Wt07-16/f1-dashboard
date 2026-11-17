package f1.ui.notify;

import f1.app.AppConfig;
import javafx.application.Platform;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 桌面通知中心 —— 系统托盘图标 + 横幅消息。
 *
 * 纯 AWT(与 JavaFX 共存于桌面进程);平台不支持托盘时自动降级为静默
 * (available()==false,设置页会置灰通知开关)。
 * notify() 线程安全,任意线程可调用;是否真的弹出受全局开关(enabled)约束。
 * 托盘右键菜单提供"退出应用"(回调切回 FX 线程执行 Platform.exit)。
 */
public final class Notifier {

    private final TrayIcon tray; // null = 当前环境无托盘
    private volatile boolean enabled;

    public Notifier(AppConfig config) {
        this.enabled = config.notifyEnabled;
        TrayIcon created = null;
        try {
            if (SystemTray.isSupported()) {
                PopupMenu menu = new PopupMenu();
                MenuItem quit = new MenuItem("退出 F1 Dashboard");
                quit.addActionListener(e -> Platform.runLater(Platform::exit));
                menu.add(quit);

                created = new TrayIcon(icon(), "F1 Dashboard — 实时赛况", menu);
                created.setImageAutoSize(true);
                SystemTray.getSystemTray().add(created);
            }
        } catch (Throwable e) {
            created = null; // 无头/受限环境:静默降级
            System.err.println("系统托盘不可用,桌面通知已降级为静默: " + e.getMessage());
        }
        this.tray = created;
    }

    /** 当前环境是否有托盘(无托盘时设置页把开关置灰) */
    public boolean available() {
        return tray != null;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    /** 发一条横幅通知;关闭或不可用时静默跳过(调用方无需关心环境) */
    public void notify(String caption, String body) {
        if (!enabled || tray == null) {
            return;
        }
        try {
            tray.displayMessage(caption, body, TrayIcon.MessageType.INFO);
        } catch (Throwable ignored) {
            // 通知系统偶发异常不影响主流程
        }
    }

    /**
     * 应用退出时移除托盘图标 —— 只在后台守护线程上做,发完即返回,绝不阻塞调用方。
     *
     * 不能在 FX 线程上直调:SystemTray.remove → CTrayIcon.dispose → Window.dispose
     * 会 invokeAndWait 去等 AWT 的 EDT,而 macOS 上 EDT 此时可能正卡在 native 的
     * CInputMethod.getNativeLocale() 里等 AppKit 主线程(被 JavaFX 占着),
     * 两边互等 → JavaFX 退不掉,只能强制退出。
     * 进程马上就结束了,系统自会回收托盘图标,所以这里不等结果。
     */
    public void close() {
        TrayIcon t = this.tray;
        if (t == null) {
            return;
        }
        Thread cleanup = new Thread(() -> {
            try {
                SystemTray.getSystemTray().remove(t);
            } catch (Throwable ignored) {
                // 清理失败无碍退出
            }
        }, "f1-tray-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    /** 16×16 方格旗图标(红白对角),菜单栏足够可辨 */
    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0xE10600)); // F1 红
        g.fillRoundRect(0, 0, 16, 16, 4, 4);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 8, 8);
        g.fillRect(8, 8, 8, 8);
        g.dispose();
        return img;
    }
}
