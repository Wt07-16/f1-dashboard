package f1.domain.port;

import f1.domain.model.DriverChampRow;
import f1.domain.model.TeamChampRow;

import java.util.List;

/**
 * 冠军积分榜端口。每站赛后赛果发布,积分即更新;
 * 不传轮次 = 最新;传轮次 = 该轮赛后的积分(轮次必须已完赛)。
 */
public interface StandingsProvider {

    List<DriverChampRow> driverStandings();

    List<DriverChampRow> driverStandings(int round);

    List<TeamChampRow> teamStandings();

    List<TeamChampRow> teamStandings(int round);
}
