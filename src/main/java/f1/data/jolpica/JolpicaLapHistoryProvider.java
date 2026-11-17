package f1.data.jolpica;

import f1.data.http.Http;
import f1.data.jolpica.dto.JolpicaDtos.Envelope;
import f1.data.jolpica.dto.JolpicaDtos.LapsDto;
import f1.data.jolpica.dto.JolpicaDtos.RaceDto;
import f1.data.jolpica.dto.JolpicaDtos.TimingDto;
import f1.domain.DataException;
import f1.domain.model.LapSample;
import f1.domain.port.LapHistoryProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static f1.data.jolpica.JolpicaMapper.parseInt;

/**
 * 逐圈历史数据(真实源):/laps.json 按"条"分页(每页上限 100 条,
 * 一条 = 一位车手的一圈,Monza 全站约 1050 条),且分页可能截断"同一圈"
 * (圈的首尾车手落在相邻两页)—— 因此这里循环拉页,并按圈号把车手合并。
 * 合并结果圈号升序、圈内记录保持原序(消费者会再按名次排)。
 *
 * 比赛进行中轮询复用同一方法:数据量小时页数少,天然省流量;
 * 页数多说明临近完赛,轮询节奏照旧即可。
 */
public final class JolpicaLapHistoryProvider implements LapHistoryProvider {

    private static final String BASE = "https://api.jolpi.ca/ergast/f1/";
    private static final int PAGE_SIZE = 100;
    /** 防御上限:约 1000 条/页 × 5 页已远超一整场比赛 */
    private static final int MAX_PAGES = 25;

    @Override
    public List<LapSample> laps(int round) {
        // 圈号 -> 该圈全部车手记录(圈内按抓取顺序;同圈跨页时追加)
        Map<Integer, List<TimingDto>> byLap = new LinkedHashMap<>();
        int offset = 0;
        int total = -1;
        for (int page = 0; page < MAX_PAGES; page++) {
            Envelope envelope = Http.getJson(
                    BASE + "current/" + round + "/laps.json?limit=" + PAGE_SIZE + "&offset=" + offset,
                    Envelope.class);
            List<RaceDto> races = envelope == null || envelope.mrData() == null
                    || envelope.mrData().raceTable() == null ? null
                    : envelope.mrData().raceTable().races();
            if (envelope != null && envelope.mrData() != null) {
                total = parseInt(envelope.mrData().total());
            }
            int added = 0;
            if (races != null && !races.isEmpty() && races.get(0).laps() != null) {
                for (LapsDto lap : races.get(0).laps()) {
                    int lapNo = parseInt(lap.number());
                    if (lapNo <= 0) {
                        continue;
                    }
                    List<TimingDto> bucket = byLap.computeIfAbsent(lapNo, k -> new ArrayList<>());
                    if (lap.timings() != null) {
                        bucket.addAll(lap.timings());
                    }
                    added += lap.timings() == null ? 0 : lap.timings().size();
                }
            }
            if (added == 0) {
                break; // 最后一页已取空(或该轮根本没有逐圈数据)
            }
            offset += added;
            if (total >= 0 && offset >= total) {
                break;
            }
        }
        if (byLap.isEmpty()) {
            throw new DataException("第 " + round + " 站逐圈数据尚未发布");
        }
        List<LapSample> out = new ArrayList<>();
        for (Map.Entry<Integer, List<TimingDto>> e : byLap.entrySet()) {
            for (TimingDto t : e.getValue()) {
                out.add(new LapSample(e.getKey(), t.driverId(),
                        parseInt(t.position()), t.time()));
            }
        }
        return out;
    }
}
