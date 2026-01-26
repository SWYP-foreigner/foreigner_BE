package core.global.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter; // lombok 사용 시

@Getter
public enum KNewsContentType {
    // 요청하신 이모지 및 대소문자 매핑 적용
    K_POP("🎧K-POP"),
    K_DRAMA("💖K-Drama"),
    K_CULTURE("🇰🇷K-Culture"),
    K_BEAUTY("💄K-Beauty"),
    K_FASHION("🧢K-Fashion");

    private final String label; // 이모지가 포함된 표시용 텍스트

    KNewsContentType(String label) {
        this.label = label;
    }

    @JsonValue
    public String getValue() {
        return name().replace("_", "-");
    }

    @JsonCreator
    public static KNewsContentType fromValue(String value) {
        if (value == null) return null;
        try {
            return KNewsContentType.valueOf(value.replace("-", "_").toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}