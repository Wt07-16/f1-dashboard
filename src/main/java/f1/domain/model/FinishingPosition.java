package f1.domain.model;

/** 单站比赛结果中的一名车手(冲线成绩)。 */
public record FinishingPosition(
        int position,
        Driver driver,
        int points,
        int laps,
        String time,   // 完赛用时 / 落后时间,字符串原样保留
        String status) { // "Finished" / "+1 Lap" / "DNF" / "Accident" ...
}
