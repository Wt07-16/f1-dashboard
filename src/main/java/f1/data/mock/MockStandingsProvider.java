package f1.data.mock;

import f1.domain.DataException;
import f1.domain.model.DriverChampRow;
import f1.domain.model.TeamChampRow;
import f1.domain.port.StandingsProvider;

import java.util.List;

/**
 * Mock 积分榜:内嵌第 13 站(Monza,2026-09-06)赛后快照。
 * 更早轮次的积分未内置(逐轮重建会产生与真实源不一致的分叉,
 * 那正是我们要避免的) —— 明确报错而不是给错数据。
 */
public final class MockStandingsProvider implements StandingsProvider {

    @Override
    public List<DriverChampRow> driverStandings() {
        return MockSnapshot.DRIVER_STANDINGS;
    }

    @Override
    public List<DriverChampRow> driverStandings(int round) {
        requireSnapshotRound(round, "车手");
        return MockSnapshot.DRIVER_STANDINGS;
    }

    @Override
    public List<TeamChampRow> teamStandings() {
        return MockSnapshot.TEAM_STANDINGS;
    }

    @Override
    public List<TeamChampRow> teamStandings(int round) {
        requireSnapshotRound(round, "车队");
        return MockSnapshot.TEAM_STANDINGS;
    }

    private static void requireSnapshotRound(int round, String what) {
        if (round != MockSnapshot.SNAPSHOT_ROUND) {
            throw new DataException("Mock 仅内置第 " + MockSnapshot.SNAPSHOT_ROUND + " 站(Monza)赛后" + what + "积分;"
                    + "更早轮次请在「真实数据源」下查看");
        }
    }
}
