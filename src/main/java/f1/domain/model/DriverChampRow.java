package f1.domain.model;

/** 车手积分榜的一行。 */
public record DriverChampRow(
        int rank,
        Driver driver,
        int points,
        int wins) {
}
