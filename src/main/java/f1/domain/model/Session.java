package f1.domain.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 比赛周末里的一个非正赛场次(练习赛 / 排位赛 / 冲刺赛)。
 * 领域层只描述"有什么场次、何时开始",不关心数据源怎么命名它。
 */
public record Session(Kind kind, Instant start) {

    /** 场次类型;label 为界面展示用的中文名 */
    public enum Kind {
        PRACTICE("练习赛"),
        SPRINT_QUALIFYING("冲刺排位赛"),
        SPRINT("冲刺赛"),
        QUALIFYING("排位赛");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA);

    /** 转本地时区的展示文本,如 "10月10日 周六 17:00";start 缺失时返回空串 */
    public String localStartText() {
        if (start == null) {
            return "";
        }
        return LOCAL.format(start.atZone(ZoneId.systemDefault()));
    }
}
