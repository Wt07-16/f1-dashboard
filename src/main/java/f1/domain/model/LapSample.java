package f1.domain.model;

/**
 * 一圈记录:某车手在某圈的位置与圈速。
 * 用于赛后回放与圈速对比(数据来自 laps.json)。
 */
public record LapSample(
        int lap,
        String driverId,
        int position,
        String time) { // 圈速文本,如 "1:28.470";缺失可为 null
}
