package f1.domain.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 赛季中的一站(round)。 */
public record RaceEntry(
        int round,
        String raceName,     // "Italian Grand Prix"
        String circuitName,  // "Autodromo Nazionale di Monza"
        String locality,     // "Monza"
        String country,      // "Italy"
        Instant startInstant, // 正赛起跑时刻(UTC),可能缺失
        boolean completed,
        List<Session> sessions) { // 同周末的非正赛场次(练习/排位/冲刺),可能为空

    public RaceEntry {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA);

    /** 转本地时区的展示文本,如 "9月6日 周日 21:00";缺失时返回空串 */
    public String localStartText() {
        if (startInstant == null) {
            return "";
        }
        return LOCAL.format(startInstant.atZone(ZoneId.systemDefault()));
    }
}
