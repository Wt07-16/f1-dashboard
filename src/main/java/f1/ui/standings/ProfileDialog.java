package f1.ui.standings;

import f1.app.F1Session;
import f1.domain.model.Driver;
import f1.domain.model.DriverChampRow;
import f1.domain.model.FinishingPosition;
import f1.domain.model.RaceEntry;
import f1.domain.model.TeamChampRow;
import f1.ui.F1Page;
import f1.ui.util.Async;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 资料卡弹窗(积分榜点击行打开):
 *   车手卡 = 名字/车号 + 车队/国籍/生日(真实源有出生日期) + 当前积分与胜场 + 近 5 站名次轨迹;
 *   车队卡 = 名称/总部所在地 + 积分与胜场 + 每站"两位车手中最好名次"的轨迹徽章。
 * 基础信息来自积分榜行本身;轨迹需逐站成绩 → 后台异步补齐(经 F1Session 的结果缓存)。
 * 弹窗纯 UI,数据全部来自 domain 模型与 {@link F1Session},不接触任何数据源实现。
 */
public final class ProfileDialog {

    /** 轨迹展示最近几站 */
    private static final int TRAIL_ROUNDS = 5;

    /** 一枚轨迹徽章:第几站 + 该站名次文本(如 "P1"/"DNF")+ 原始状态(悬停提示) */
    private record Trail(int round, String text, String detail, boolean dnf) {
    }

    private ProfileDialog() {
    }

    public static void showDriver(F1Session session, DriverChampRow row, Window owner) {
        Driver d = row.driver();
        VBox body = new VBox(12);
        body.getStyleClass().add("profile-body");
        body.setPadding(new Insets(6, 4, 2, 4));

        // ---- 头部:车号徽章 + 大字姓名 + 榜位 ----
        Label num = new Label("#" + d.numberText());
        num.getStyleClass().add("profile-num");
        Label name = new Label(d.fullName());
        name.getStyleClass().add("profile-name");
        Label rank = new Label("积分榜第 " + row.rank() + " 位");
        rank.getStyleClass().add("profile-rank");
        HBox head = new HBox(10, num, name);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(3, head, rank);
        body.getChildren().add(top);

        // ---- 元信息:车队 / 国籍 / 生日与年龄 ----
        String meta = "车队 " + (d.teamName() == null ? "—" : d.teamName())
                + "  ·  国籍 " + (d.nationality() == null || d.nationality().isBlank()
                        ? "—" : d.nationality())
                + "  ·  " + birthText(d);
        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("profile-meta");
        metaLabel.setWrapText(true);
        body.getChildren().add(metaLabel);

        // ---- 状态行:积分 / 胜场 ----
        Label stats = new Label("🏆 " + row.points() + " 分" + "   ·   🥇 " + row.wins() + " 场胜利");
        stats.getStyleClass().add("profile-stats");
        body.getChildren().add(stats);

        // ---- 轨迹区 ----
        body.getChildren().add(trailSection(session,
                rounds -> trailOfDriver(session, rounds, d.id()),
                roundList(session)));
        show(session, body, owner, d.fullName() + " · 资料卡");
    }

    public static void showTeam(F1Session session, TeamChampRow row, Window owner) {
        var t = row.team();
        VBox body = new VBox(12);
        body.getStyleClass().add("profile-body");
        body.setPadding(new Insets(6, 4, 2, 4));

        Label badge = new Label("🏁");
        badge.getStyleClass().add("profile-num");
        Label name = new Label(t.name());
        name.getStyleClass().add("profile-name");
        Label rank = new Label("车队积分榜第 " + row.rank() + " 位");
        rank.getStyleClass().add("profile-rank");
        VBox top = new VBox(3, new HBox(10, badge, name), rank);
        body.getChildren().add(top);

        Label metaLabel = new Label("总部所在地  " + t.nationality());
        metaLabel.getStyleClass().add("profile-meta");
        body.getChildren().add(metaLabel);

        Label stats = new Label("🏆 " + row.points() + " 分" + "   ·   🥇 " + row.wins() + " 场胜利");
        stats.getStyleClass().add("profile-stats");
        body.getChildren().add(stats);

        // 车队轨迹 = 每站两位车手中较好名次
        body.getChildren().add(trailSection(session,
                rounds -> trailOfTeam(session, rounds, t.id(), t.name()),
                roundList(session)));
        show(session, body, owner, t.name() + " · 资料卡");
    }

    // ================= 弹窗骨架 =================

    private static void show(F1Session session, VBox body, Window owner, String title) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getStylesheets().add(
                F1Page.class.getResource("theme.css").toExternalForm());
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setMinWidth(430);
        dialog.getDialogPane().setPrefWidth(470);

        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(close);
        Button btn = (Button) dialog.getDialogPane().lookupButton(close);
        btn.getStyleClass().add("ghost");
        dialog.show();
    }

    private static String birthText(Driver d) {
        LocalDate dob = d.dateOfBirth();
        if (dob == null) {
            return "生日 —(演示数据不含出生日期)";
        }
        int age = Period.between(dob, LocalDate.now()).getYears();
        return "生日 " + dob + " · " + age + " 岁";
    }

    // ================= 轨迹 =================

    /** 已完赛轮次(升序),供轨迹取最近 TRAIL_ROUNDS 站 */
    private static List<RaceEntry> roundList(F1Session session) {
        try {
            return session.season().schedule().stream()
                    .filter(RaceEntry::completed)
                    .sorted(Comparator.comparingInt(RaceEntry::round))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 标题 + 徽章行 + 加载状态行;轨迹由 loader 后台计算,完成后填充徽章 */
    private static VBox trailSection(F1Session session, java.util.function.Function<List<RaceEntry>, List<Trail>> loader,
                                     List<RaceEntry> rounds) {
        Label title = new Label("最近 " + Math.min(TRAIL_ROUNDS, rounds.size()) + " 站名次");
        title.getStyleClass().add("profile-trail-title");

        Label loading = new Label(rounds.isEmpty()
                ? "尚无已完赛站次"
                : "⏳ 轨迹加载中…");
        loading.getStyleClass().add("profile-trail-status");

        HBox badges = new HBox(8);
        badges.setAlignment(Pos.CENTER_LEFT);
        badges.setVisible(false);
        badges.setManaged(false);

        VBox box = new VBox(8, title, loading, badges);
        if (rounds.isEmpty()) {
            return box;
        }
        int from = Math.max(0, rounds.size() - TRAIL_ROUNDS);
        List<RaceEntry> trail = rounds.subList(from, rounds.size());
        Async.run(session.executor(), () -> loader.apply(trail),
                trailData -> {
                    badges.getChildren().setAll(trail.stream()
                            .map(r -> trailData.stream()
                                    .filter(t -> t.round() == r.round())
                                    .findFirst().orElse(null))
                            .filter(t -> t != null)
                            .map(ProfileDialog::badgeOf).toList());
                    loading.setVisible(false);
                    loading.setManaged(false);
                    badges.setVisible(true);
                    badges.setManaged(true);
                },
                error -> {
                    loading.setText("轨迹不可用:" + error.getMessage()
                            + " —— 逐站成绩需要真实数据源(MOCK 源不提供),切到真实数据源后重试");
                    loading.getStyleClass().add("error-text");
                });
        return box;
    }

    /** 车手轨迹:每站该车手的完赛/退赛情况 */
    private static List<Trail> trailOfDriver(F1Session session, List<RaceEntry> trail, String driverId) {
        List<Trail> out = new ArrayList<>(trail.size());
        for (RaceEntry race : trail) {
            FinishingPosition fp = session.raceResultsCached(race.round()).stream()
                    .filter(r -> r.driver().id().equals(driverId))
                    .findFirst().orElse(null);
            out.add(fp == null ? new Trail(race.round(), "—", "该站未参赛", false)
                    : ofPosition(race.round(), fp));
        }
        return out;
    }

    /** 车队轨迹:每站取两位车手中较好的名次(车队层面单站表现) */
    private static List<Trail> trailOfTeam(F1Session session, List<RaceEntry> trail, String teamId, String teamName) {
        List<Trail> out = new ArrayList<>(trail.size());
        for (RaceEntry race : trail) {
            int best = Integer.MAX_VALUE;
            FinishingPosition bestFp = null;
            for (FinishingPosition fp : session.raceResultsCached(race.round())) {
                Driver d = fp.driver();
                boolean mine = (d.teamId() != null && d.teamId().equals(teamId))
                        || (teamName != null && teamName.equals(d.teamName()));
                if (mine && fp.position() > 0 && fp.position() < best) {
                    best = fp.position();
                    bestFp = fp;
                }
            }
            out.add(bestFp == null ? new Trail(race.round(), "—", "该站无成绩", false)
                    : ofPosition(race.round(), bestFp));
        }
        return out;
    }

    /** 一行成绩 → 徽章数据:套圈完赛也算完赛;其余非 Finished 统一标 DNF(原状态放悬停) */
    private static Trail ofPosition(int round, FinishingPosition fp) {
        String status = fp.status() == null ? "" : fp.status();
        if (status.startsWith("+") || status.equalsIgnoreCase("Finished") || status.isBlank()) {
            return new Trail(round, "P" + fp.position(), status.isBlank() ? "完赛" : status, false);
        }
        return new Trail(round, "DNF", status, true);
    }

    private static Label badgeOf(Trail t) {
        Label b = new Label(t.text());
        b.getStyleClass().add("profile-badge");
        if (t.dnf()) {
            b.getStyleClass().add("profile-badge-dnf");
        }
        b.setTooltip(new Tooltip("第 " + t.round() + " 站 · " + t.detail()));
        return b;
    }
}
