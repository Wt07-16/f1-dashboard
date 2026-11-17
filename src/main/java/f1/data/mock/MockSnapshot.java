package f1.data.mock;

import f1.domain.model.Driver;
import f1.domain.model.DriverChampRow;
import f1.domain.model.RaceEntry;
import f1.domain.model.Team;
import f1.domain.model.TeamChampRow;

import java.time.Instant;
import java.util.List;

/**
 * 2026 赛季真实快照(Mock 数据源底座)。
 * 抓取时间 2026-09-08(Jolpica 公开 API),口径 = 第 13 站(Monza)赛后。
 * 内容不会再更新 —— 离线时按此展示,并明示"快照"标签;
 * 在线请用真实源。生成文件,请勿手改。
 */
public final class MockSnapshot {

    /** 快照对应的轮次(第 13 站,2026-09-06 Monza) */
    public static final int SNAPSHOT_ROUND = 13;

    public static final List<RaceEntry> SCHEDULE = List.of(
            race(1, "Australian Grand Prix", "Albert Park Grand Prix Circuit", "Melbourne", "Australia", "2026-03-08T04:00:00Z"),
            race(2, "Chinese Grand Prix", "Shanghai International Circuit", "Shanghai", "China", "2026-03-15T07:00:00Z"),
            race(3, "Japanese Grand Prix", "Suzuka Circuit", "Suzuka", "Japan", "2026-03-29T05:00:00Z"),
            race(4, "Miami Grand Prix", "Miami International Autodrome", "Miami", "USA", "2026-05-03T20:00:00Z"),
            race(5, "Canadian Grand Prix", "Circuit Gilles Villeneuve", "Montreal", "Canada", "2026-05-24T20:00:00Z"),
            race(6, "Monaco Grand Prix", "Circuit de Monaco", "Monte Carlo", "Monaco", "2026-06-07T13:00:00Z"),
            race(7, "Barcelona Grand Prix", "Circuit de Barcelona-Catalunya", "Barcelona", "Spain", "2026-06-14T13:00:00Z"),
            race(8, "Austrian Grand Prix", "Red Bull Ring", "Spielberg", "Austria", "2026-06-28T13:00:00Z"),
            race(9, "British Grand Prix", "Silverstone Circuit", "Silverstone", "UK", "2026-07-05T14:00:00Z"),
            race(10, "Belgian Grand Prix", "Circuit de Spa-Francorchamps", "Spa", "Belgium", "2026-07-19T13:00:00Z"),
            race(11, "Hungarian Grand Prix", "Hungaroring", "Budapest", "Hungary", "2026-07-26T13:00:00Z"),
            race(12, "Dutch Grand Prix", "Circuit Park Zandvoort", "Zandvoort", "Netherlands", "2026-08-23T13:00:00Z"),
            race(13, "Italian Grand Prix", "Autodromo Nazionale di Monza", "Monza", "Italy", "2026-09-06T13:00:00Z"),
            race(14, "Spanish Grand Prix", "Madring", "Madrid", "Spain", "2026-09-13T13:00:00Z"),
            race(15, "Azerbaijan Grand Prix", "Baku City Circuit", "Baku", "Azerbaijan", "2026-09-26T11:00:00Z"),
            race(16, "Bahrain Grand Prix in Malaysia", "Sepang International Circuit", "Kuala Lumpur", "Malaysia", "2026-10-04T07:00:00Z"),
            race(17, "Singapore Grand Prix", "Marina Bay Street Circuit", "Marina Bay", "Singapore", "2026-10-11T12:00:00Z"),
            race(18, "United States Grand Prix", "Circuit of the Americas", "Austin", "USA", "2026-10-25T20:00:00Z"),
            race(19, "Mexico City Grand Prix", "Autódromo Hermanos Rodríguez", "Mexico City", "Mexico", "2026-11-01T20:00:00Z"),
            race(20, "Brazilian Grand Prix", "Autódromo José Carlos Pace", "São Paulo", "Brazil", "2026-11-08T17:00:00Z"),
            race(21, "Las Vegas Grand Prix", "Las Vegas Strip Street Circuit", "Las Vegas", "USA", "2026-11-22T04:00:00Z"),
            race(22, "Qatar Grand Prix", "Losail International Circuit", "Lusail", "Qatar", "2026-11-29T16:00:00Z"),
            race(23, "Abu Dhabi Grand Prix", "Yas Marina Circuit", "Abu Dhabi", "UAE", "2026-12-06T13:00:00Z"));

    private static RaceEntry race(int r, String name, String circuit, String loc,
                                   String country, String start) {
        boolean completed = r <= 13;
        return new RaceEntry(r, name, circuit, loc, country,
                start == null ? null : Instant.parse(start), completed,
                List.of()); // Mock 快照只存正赛骨架,不带场次明细
    }

    /** 第 13 站赛后车手积分榜(完整 23 席) */
    public static final List<DriverChampRow> DRIVER_STANDINGS = List.of(
            dr(1, "antonelli", "ANT", 12, "Andrea Kimi", "Antonelli", "Italian", "mercedes", "Mercedes", 267, 7),
            dr(2, "russell", "RUS", 63, "George", "Russell", "British", "mercedes", "Mercedes", 201, 2),
            dr(3, "hamilton", "HAM", 44, "Lewis", "Hamilton", "British", "ferrari", "Ferrari", 191, 1),
            dr(4, "norris", "NOR", 1, "Lando", "Norris", "British", "mclaren", "McLaren", 171, 2),
            dr(5, "leclerc", "LEC", 16, "Charles", "Leclerc", "Monegasque", "ferrari", "Ferrari", 155, 1),
            dr(6, "max_verstappen", "VER", 3, "Max", "Verstappen", "Dutch", "red_bull", "Red Bull", 127, 0),
            dr(7, "piastri", "PIA", 81, "Oscar", "Piastri", "Australian", "mclaren", "McLaren", 116, 0),
            dr(8, "hadjar", "HAD", 6, "Isack", "Hadjar", "French", "red_bull", "Red Bull", 71, 0),
            dr(9, "lawson", "LAW", 30, "Liam", "Lawson", "New Zealander", "rb", "RB F1 Team", 51, 0),
            dr(10, "gasly", "GAS", 10, "Pierre", "Gasly", "French", "alpine", "Alpine F1 Team", 41, 0),
            dr(11, "arvid_lindblad", "LIN", 41, "Arvid", "Lindblad", "British", "rb", "RB F1 Team", 29, 0),
            dr(12, "colapinto", "COL", 43, "Franco", "Colapinto", "Argentine", "alpine", "Alpine F1 Team", 21, 0),
            dr(13, "bearman", "BEA", 87, "Oliver", "Bearman", "British", "haas", "Haas F1 Team", 18, 0),
            dr(14, "bortoleto", "BOR", 5, "Gabriel", "Bortoleto", "Brazilian", "audi", "Audi", 10, 0),
            dr(15, "hulkenberg", "HUL", 27, "Nico", "Hülkenberg", "German", "audi", "Audi", 6, 0),
            dr(16, "sainz", "SAI", 55, "Carlos", "Sainz", "Spanish", "williams", "Williams", 6, 0),
            dr(17, "albon", "ALB", 23, "Alexander", "Albon", "Thai", "williams", "Williams", 5, 0),
            dr(18, "ocon", "OCO", 31, "Esteban", "Ocon", "French", "haas", "Haas F1 Team", 3, 0),
            dr(19, "alonso", "ALO", 14, "Fernando", "Alonso", "Spanish", "aston_martin", "Aston Martin", 3, 0),
            dr(20, "tsunoda", "TSU", 22, "Yuki", "Tsunoda", "Japanese", "rb", "RB F1 Team", 1, 0),
            dr(21, "stroll", "STR", 18, "Lance", "Stroll", "Canadian", "aston_martin", "Aston Martin", 0, 0),
            dr(22, "bottas", "BOT", 77, "Valtteri", "Bottas", "Finnish", "cadillac", "Cadillac F1 Team", 0, 0),
            dr(23, "perez", "PER", 11, "Sergio", "Pérez", "Mexican", "cadillac", "Cadillac F1 Team", 0, 0));

    private static DriverChampRow dr(int rank, String id, String code, int number, String given,
                                     String family, String nat, String teamId, String teamName,
                                     int points, int wins) {
        return new DriverChampRow(rank,
                new Driver(id, code, number, given, family, nat, null, teamId, teamName),
                points, wins);
    }

    /** 第 13 站赛后车队积分榜(完整 11 席) */
    public static final List<TeamChampRow> TEAM_STANDINGS = List.of(
            tr(1, "mercedes", "Mercedes", "German", 468, 9),
            tr(2, "ferrari", "Ferrari", "Italian", 346, 2),
            tr(3, "mclaren", "McLaren", "British", 287, 2),
            tr(4, "red_bull", "Red Bull", "Austrian", 204, 0),
            tr(5, "rb", "RB F1 Team", "Italian", 75, 0),
            tr(6, "alpine", "Alpine F1 Team", "French", 62, 0),
            tr(7, "haas", "Haas F1 Team", "American", 21, 0),
            tr(8, "audi", "Audi", "German", 16, 0),
            tr(9, "williams", "Williams", "British", 11, 0),
            tr(10, "aston_martin", "Aston Martin", "British", 3, 0),
            tr(11, "cadillac", "Cadillac F1 Team", "American", 0, 0));

    private static TeamChampRow tr(int rank, String id, String name, String nat,
                                   int points, int wins) {
        return new TeamChampRow(rank, new Team(id, name, nat), points, wins);
    }

    private MockSnapshot() {
    }
}
