package core.global.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AppSettingKey {

    FEEDBACK_URL("FEEDBACK_URL"),
    BUG_REPORT_URL("BUG_REPORT_URL");

    private final String key;
}