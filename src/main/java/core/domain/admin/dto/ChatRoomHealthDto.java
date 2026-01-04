package core.domain.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatRoomHealthDto {
    private long totalPrivateRooms;
    private long emptyRooms;
    private long oneWayRooms;
    private long twoWayRooms;

    public double getEmptyRatio() { return calculateRatio(emptyRooms); }
    public double getOneWayRatio() { return calculateRatio(oneWayRooms); }
    public double getTwoWayRatio() { return calculateRatio(twoWayRooms); }

    private double calculateRatio(long count) {
        if (totalPrivateRooms == 0) return 0.0;
        return Math.round((double) count / totalPrivateRooms * 100.0 * 10) / 10.0;
    }
}
