package f1.data.jolpica;

import f1.data.http.Http;
import f1.data.jolpica.dto.JolpicaDtos.ConstructorStanding;
import f1.data.jolpica.dto.JolpicaDtos.DriverStanding;
import f1.data.jolpica.dto.JolpicaDtos.Envelope;
import f1.data.jolpica.dto.JolpicaDtos.StandingsList;
import f1.data.jolpica.dto.JolpicaDtos.StandingsTable;
import f1.domain.DataException;
import f1.domain.model.DriverChampRow;
import f1.domain.model.TeamChampRow;
import f1.domain.port.StandingsProvider;

import java.util.List;

/**
 * 车手/车队冠军积分榜的真实数据源(Jolpica,免费)。
 * 每站赛果发布后即可查到最新积分(current = 最新已完赛轮次)。
 */
public final class JolpicaStandingsProvider implements StandingsProvider {

    private static final String BASE = "https://api.jolpi.ca/ergast/f1/";

    @Override
    public List<DriverChampRow> driverStandings() {
        return driverStandingsUrl(BASE + "current/driverstandings.json");
    }

    @Override
    public List<DriverChampRow> driverStandings(int round) {
        return driverStandingsUrl(BASE + "current/" + round + "/driverstandings.json");
    }

    @Override
    public List<TeamChampRow> teamStandings() {
        return teamStandingsUrl(BASE + "current/constructorstandings.json");
    }

    @Override
    public List<TeamChampRow> teamStandings(int round) {
        return teamStandingsUrl(BASE + "current/" + round + "/constructorstandings.json");
    }

    private static List<DriverChampRow> driverStandingsUrl(String url) {
        List<DriverStanding> rows = findDriverRows(url);
        return rows.stream().map(row -> {
            var driver = JolpicaMapper.driver(row.driver(),
                    row.constructors() == null || row.constructors().isEmpty() ? null : row.constructors().get(0));
            return new DriverChampRow(
                    JolpicaMapper.parseInt(row.position()),
                    driver,
                    JolpicaMapper.parseInt(row.points()),
                    JolpicaMapper.parseInt(row.wins()));
        }).toList();
    }

    private static List<TeamChampRow> teamStandingsUrl(String url) {
        Envelope envelope = Http.getJson(url, Envelope.class);
        StandingsList list = findList(envelope);
        if (list == null || list.constructorStandings() == null) {
            throw new DataException("车队积分数据为空");
        }
        List<ConstructorStanding> rows = list.constructorStandings();
        return rows.stream().map(row -> new TeamChampRow(
                JolpicaMapper.parseInt(row.position()),
                JolpicaMapper.team(row.constructor()),
                JolpicaMapper.parseInt(row.points()),
                JolpicaMapper.parseInt(row.wins()))).toList();
    }

    private static List<DriverStanding> findDriverRows(String url) {
        Envelope envelope = Http.getJson(url, Envelope.class);
        StandingsList list = findList(envelope);
        if (list == null || list.driverStandings() == null) {
            throw new DataException("车手积分数据为空");
        }
        return list.driverStandings();
    }

    private static StandingsList findList(Envelope envelope) {
        if (envelope == null || envelope.mrData() == null) {
            return null;
        }
        StandingsTable table = envelope.mrData().standingsTable();
        if (table == null || table.standingsLists() == null || table.standingsLists().isEmpty()) {
            return null;
        }
        return table.standingsLists().get(0);
    }
}
