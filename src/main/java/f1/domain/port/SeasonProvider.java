package f1.domain.port;

import f1.domain.model.FinishingPosition;
import f1.domain.model.RaceEntry;

import java.util.List;

/**
 * 赛季信息端口:全季赛历 + 单站成绩。
 * 数据层(真实 Jolpica / Mock)实现它;UI 只依赖本接口 —— 换实现不碰 UI。
 * 说明:方法为阻塞式,调用方应通过后台线程执行(见 ui 层 Async 工具)。
 */
public interface SeasonProvider {

    /** 全季赛历(按轮次升序,含是否已完赛与起跑时刻) */
    List<RaceEntry> schedule();

    /** 指定轮次的正式成绩(冲线名单);未发布时抛 DataException */
    List<FinishingPosition> raceResults(int round);
}
