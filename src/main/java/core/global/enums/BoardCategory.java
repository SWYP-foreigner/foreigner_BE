package core.global.enums;

import lombok.Getter;

@Getter
public enum BoardCategory {
    ALL("ALL"),
    NEWS("News"),
    TIP("Tip"),
    QNA("Q&A"),
    EVENT("Event"),
    FREE_TALK("Free talk"),
    ACTIVITY("Activity");

    private final String displayName;

    BoardCategory(String displayName) {
        this.displayName = displayName;
    }

    public static BoardCategory from(String raw) {
        if (raw == null) throw new IllegalArgumentException("boardCode is null");
        String norm = raw.trim().toUpperCase()
                .replace("&","")
                .replace(" ", "")
                .replace("-", "_");
        return switch (norm) {
            case "ALL" -> ALL;
            case "NEWS" -> NEWS;
            case "TIP" -> TIP;
            case "QNA", "QANDA", "QA" -> QNA;
            case "EVENT" -> EVENT;
            case "FREE", "FREETALK" -> FREE_TALK;
            case "ACTIVITY" -> ACTIVITY;
            default -> throw new IllegalArgumentException("INVALID_CATEGORY: " + raw);
        };
    }
}
