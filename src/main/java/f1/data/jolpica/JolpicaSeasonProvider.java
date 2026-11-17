package f1.data.jolpica;

import f1.data.http.Http;
import f1.data.jolpica.dto.JolpicaDtos.Envelope;
import f1.data.jolpica.dto.JolpicaDtos.RaceDto;
import f1.data.jolpica.dto.JolpicaDtos.ResultDto;
import f1.data.jolpica.dto.JolpicaDtos.SessionDto;
import f1.domain.DataException;
import f1.domain.model.FinishingPosition;
import f1.domain.model.RaceEntry;
import f1.domain.model.Session;
import f1.domain.port.SeasonProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static f1.data.jolpica.JolpicaMapper.parseDateOrNull;
import static f1.data.jolpica.JolpicaMapper.parseInt;

/**
 * 赛历与单站成绩的真实数据源(Jolpica / Ergast 格式,免费)。
 */
public final class JolpicaSeasonProvider implements SeasonProvider {

    private static final String BASE = "https://api.jolpi.ca/ergast/f1/";

    @Override
    public List<RaceEntry> schedule() {
        Envelope envelope = Http.getJson(BASE + "current.json", Envelope.class);
        List<RaceDto> races = envelope == null || envelope.mrData() == null
                || envelope.mrData().raceTable() == null ? null
                : envelope.mrData().raceTable().races();
        if (races == null) {
            throw new DataException("赛历数据为空,数据源可能暂时不可用");
        }
        return races.stream().map(JolpicaSeasonProvider::mapRace).toList();
    }

    @Override
    public List<FinishingPosition> raceResults(int round) {
        Envelope envelope = Http.getJson(BASE + "current/" + round + "/results.json", Envelope.class);
        List<ResultDto> results = envelope == null || envelope.mrData() == null
                || envelope.mrData().raceTable() == null
                || envelope.mrData().raceTable().races().isEmpty() ? null
                : envelope.mrData().raceTable().races().get(0).results();
        if (results == null || results.isEmpty()) {
            throw new DataException("第 " + round + " 站成绩尚未发布");
        }
        return results.stream().map(JolpicaSeasonProvider::mapResult).toList();
    }

    private static RaceEntry mapRace(RaceDto dto) {
        Instant start = JolpicaMapper.parseRaceInstant(dto.date(), dto.time());
        boolean hasResults = dto.results() != null && !dto.results().isEmpty();
        // 正赛结束后若数据源尚未发布成绩,按已结束处理(赛程上不再显示倒计时)
        boolean longPast = start != null && start.plus(java.time.Duration.ofHours(4)).isBefore(Instant.now());
        return new RaceEntry(
                parseInt(dto.round()),
                dto.raceName(),
                dto.circuit() == null ? "" : dto.circuit().circuitName(),
                dto.circuit() == null || dto.circuit().location() == null ? "" : dto.circuit().location().locality(),
                dto.circuit() == null || dto.circuit().location() == null ? "" : dto.circuit().location().country(),
                start,
                hasResults || longPast,
                sessions(dto));
    }

    /**
     * 把该站的各非正赛场次收集成按时间排序的列表。
     * 冲刺赛周末有 Sprint/SprintQualifying,常规周末有 First/Second/ThirdPractice;
     * 缺失或格式坏的场次直接跳过,不影响整站解析。
     */
    private static List<Session> sessions(RaceDto dto) {
        List<Session> out = new ArrayList<>(6);
        addSession(out, dto.firstPractice(), Session.Kind.PRACTICE);
        addSession(out, dto.secondPractice(), Session.Kind.PRACTICE);
        addSession(out, dto.thirdPractice(), Session.Kind.PRACTICE);
        addSession(out, dto.sprintQualifying(), Session.Kind.SPRINT_QUALIFYING);
        addSession(out, dto.sprint(), Session.Kind.SPRINT);
        addSession(out, dto.qualifying(), Session.Kind.QUALIFYING);
        out.sort(Comparator.comparing(Session::start));
        return out;
    }

    private static void addSession(List<Session> out, SessionDto dto, Session.Kind kind) {
        if (dto == null) {
            return;
        }
        Instant start = JolpicaMapper.parseRaceInstant(dto.date(), dto.time());
        if (start != null) {
            out.add(new Session(kind, start));
        }
    }

    private static FinishingPosition mapResult(ResultDto dto) {
        String time = dto.time() == null ? null : dto.time().time();
        return new FinishingPosition(
                parseInt(dto.position()),
                JolpicaMapper.driver(dto.driver(), dto.constructor()),
                parseInt(dto.points()),
                parseInt(dto.laps()),
                time,
                dto.status());
    }
}
