package f1.domain.model;

import java.time.LocalDate;

/**
 * 车手(领域模型,与具体数据源无关)。
 * 数据层从 Jolpica 等来源映射为这个形态,UI 只消费它。
 */
public record Driver(
        String id,          // 稳定 ID,如 "max_verstappen"
        String code,        // 三字母缩写,如 "VER"
        Integer number,     // 车号,可能缺失(历史数据)时为 null
        String givenName,
        String familyName,
        String nationality, // 如 "Dutch"
        LocalDate dateOfBirth,
        String teamId,
        String teamName) {

    public String fullName() {
        return givenName + " " + familyName;
    }

    /** 表格等紧凑场景的显示名:以姓为主 */
    public String shortName() {
        return familyName == null || familyName.isBlank() ? fullName() : familyName;
    }

    public String numberText() {
        return number == null ? "—" : String.valueOf(number);
    }
}
