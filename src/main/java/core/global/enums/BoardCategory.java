package core.global.enums;

import lombok.Getter;

@Getter
public enum BoardCategory {
    ALL("ALL"),
    QUIZ("Quiz"),
    VOTE("Vote"),
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
}
