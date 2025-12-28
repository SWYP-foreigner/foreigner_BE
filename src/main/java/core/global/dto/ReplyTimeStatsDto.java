package core.global.dto;

public record ReplyTimeStatsDto(
        long avgReplySeconds,
        long totalReplyCount
) {
    public String getFormattedTime() {
        if (avgReplySeconds == 0) return "데이터 없음";

        long hours = avgReplySeconds / 3600;
        long minutes = (avgReplySeconds % 3600) / 60;
        long seconds = avgReplySeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("시간 ");
        if (minutes > 0) sb.append(minutes).append("분 ");
        sb.append(seconds).append("초");

        return sb.toString();
    }
}
