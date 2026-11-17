package f1.domain.model;

/**
 * 实时流中的一个位置快照:某车手在某一时刻的名次。
 * 由实时源(Mock 模拟器 / 真实轮询 / 回放引擎)持续推送。
 */
public record PositionUpdate(
        String driverId,
        int position,   // 1-based 名次
        int lap,        // 当前圈数(未知/未开始可为 0)
        String lapTime, // 该车手上一圈圈速文本,如 "1:28.470";模拟源可为 null
        long timestampMs) {
}
