package f1.domain.port;

import f1.domain.model.LapSample;

import java.util.List;

/**
 * 逐圈数据端口:某已完赛轮次全场的圈速/位置时间线。
 * 用途:赛后回放实时页、圈速对比图(M5)。
 */
public interface LapHistoryProvider {

    /** 指定轮次全部圈速样本(按圈号升序);数据未发布时抛 DataException */
    List<LapSample> laps(int round);
}
