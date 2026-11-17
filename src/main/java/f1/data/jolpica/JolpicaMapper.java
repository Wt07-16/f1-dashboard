package f1.data.jolpica;

import f1.data.jolpica.dto.JolpicaDtos.ConstructorDto;
import f1.data.jolpica.dto.JolpicaDtos.DriverDto;
import f1.domain.model.Driver;
import f1.domain.model.Team;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Jolpica DTO → 领域模型。所有解析都在这里兜底:
 * 缺字段/坏格式给 null 或默认值,绝不因为单个脏数据拖垮整页。
 */
final class JolpicaMapper {

    private JolpicaMapper() {
    }

    static Driver driver(DriverDto dto, ConstructorDto team) {
        return new Driver(
                dto.driverId(),
                dto.code(),
                parseIntOrNull(dto.permanentNumber()),
                dto.givenName(),
                dto.familyName(),
                dto.nationality(),
                parseDateOrNull(dto.dateOfBirth()),
                team == null ? null : team.constructorId(),
                team == null ? null : team.name());
    }

    static Team team(ConstructorDto dto) {
        return new Team(dto.constructorId(), dto.name(), dto.nationality());
    }

    static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseDateOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "2026-09-06" + "13:00:00Z" → Instant;任一缺失或格式坏则返回 null */
    static java.time.Instant parseRaceInstant(String date, String time) {
        if (date == null || time == null || date.isBlank() || time.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(date + "T" + time).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
