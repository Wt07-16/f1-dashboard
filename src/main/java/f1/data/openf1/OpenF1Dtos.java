package f1.data.openf1;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenF1 API (https://api.openf1.org/v1) JSON 响应的 DTO 层。
 * 覆盖全部五种场次(练习赛 / 冲刺排位赛 / 冲刺赛 / 排位赛 / 正赛)的场次信息、车手、实时名次与圈速。
 */
public final class OpenF1Dtos {

    private OpenF1Dtos() {
    }

    /** /v1/sessions 单条场次元数据 */
    public record SessionDto(
            @JsonProperty("session_key") Integer sessionKey,
            @JsonProperty("session_type") String sessionType,
            @JsonProperty("session_name") String sessionName,
            @JsonProperty("date_start") String dateStart,
            @JsonProperty("date_end") String dateEnd,
            @JsonProperty("country_name") String countryName,
            @JsonProperty("location") String location,
            @JsonProperty("circuit_short_name") String circuitShortName,
            @JsonProperty("year") Integer year,
            @JsonProperty("is_cancelled") Boolean isCancelled) {
    }

    /** /v1/drivers 车手信息(含车号、三字码、车队名) */
    public record DriverDto(
            @JsonProperty("driver_number") Integer driverNumber,
            @JsonProperty("name_acronym") String nameAcronym,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("team_name") String teamName) {
    }

    /** /v1/position 实时名次变动记录 */
    public record PositionDto(
            @JsonProperty("driver_number") Integer driverNumber,
            @JsonProperty("position") Integer position,
            @JsonProperty("date") String date) {
    }

    /** /v1/laps 单圈记录(lap_duration 单位为秒,如 91.234) */
    public record LapDto(
            @JsonProperty("driver_number") Integer driverNumber,
            @JsonProperty("lap_number") Integer lapNumber,
            @JsonProperty("lap_duration") Double lapDuration,
            @JsonProperty("is_pit_out_lap") Boolean isPitOutLap,
            @JsonProperty("date_start") String dateStart) {
    }

    /** POST https://api.openf1.org/token 返回的 OAuth2 令牌 */
    public record TokenDto(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Integer expiresIn) {
    }
}
