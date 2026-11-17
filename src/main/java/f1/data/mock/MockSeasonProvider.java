package f1.data.mock;

import f1.domain.DataException;
import f1.domain.model.FinishingPosition;
import f1.domain.model.RaceEntry;
import f1.domain.port.SeasonProvider;

import java.util.List;

/**
 * Mock 赛历:内嵌 2026 全季 23 轮真实快照。
 * 离线可展示赛历与倒计时;单站成绩未内置 —— 给出明确提示(而不是假数据)。
 */
public final class MockSeasonProvider implements SeasonProvider {

    @Override
    public List<RaceEntry> schedule() {
        return MockSnapshot.SCHEDULE;
    }

    @Override
    public List<FinishingPosition> raceResults(int round) {
        throw new DataException("Mock 快照未内置第 " + round + " 站成绩 —— 请在「真实数据源」下查看");
    }
}
