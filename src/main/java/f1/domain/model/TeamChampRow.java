package f1.domain.model;

/** 车队积分榜的一行。 */
public record TeamChampRow(
        int rank,
        Team team,
        int points,
        int wins) {
}
