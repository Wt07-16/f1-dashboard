package f1.data.jolpica.dto;

import java.util.List;

/**
 * Jolpica(Ergast 格式)JSON 响应的 DTO 层。
 * 纯数据容器,与领域模型分离;解析容错(大小写不敏感/忽略未知字段)由全局 Json 配置负责。
 */
public final class JolpicaDtos {

    private JolpicaDtos() {
    }

    /** 最外层 {"MRData": {...}};积分榜与赛历/成绩共用 */
    public record MrData(String total, StandingsTable standingsTable, RaceTable raceTable) {
    }

    public record Envelope(MrData mrData) {
    }

    // ---- 积分榜 ----

    public record StandingsTable(String season, String round, List<StandingsList> standingsLists) {
    }

    public record StandingsList(String season, String round,
                                List<DriverStanding> driverStandings,
                                List<ConstructorStanding> constructorStandings) {
    }

    public record DriverStanding(String position, String points, String wins,
                                 DriverDto driver, List<ConstructorDto> constructors) {
    }

    public record ConstructorStanding(String position, String points, String wins,
                                      ConstructorDto constructor) {
    }

    // ---- 赛历 / 成绩 ----

    public record RaceTable(String season, List<RaceDto> races) {
    }

    public record RaceDto(String round, String raceName, String date, String time,
                          CircuitDto circuit, List<ResultDto> results, List<LapsDto> laps,
                          SessionDto firstPractice, SessionDto secondPractice,
                          SessionDto thirdPractice, SessionDto qualifying,
                          SessionDto sprint, SessionDto sprintQualifying) {
    }

    /** 赛历里的单个场次(FirstPractice/SecondPractice/Qualifying/Sprint 等共用此形状) */
    public record SessionDto(String date, String time) {
    }

    /** /laps.json:每圈每位车手的位置与圈速 */
    public record LapsDto(String number, List<TimingDto> timings) {
    }

    public record TimingDto(String driverId, String time, String position) {
    }

    public record CircuitDto(String circuitName, LocationDto location) {
    }

    public record LocationDto(String locality, String country) {
    }

    public record ResultDto(String number, String position, String points,
                            String laps, String status, TimeDto time,
                            DriverDto driver, ConstructorDto constructor) {
    }

    public record TimeDto(String millis, String time) {
    }

    // ---- 车手 / 车队(多个节点复用) ----

    public record DriverDto(String driverId, String permanentNumber, String code, String url,
                            String givenName, String familyName, String dateOfBirth, String nationality) {
    }

    public record ConstructorDto(String constructorId, String url, String name, String nationality) {
    }
}
