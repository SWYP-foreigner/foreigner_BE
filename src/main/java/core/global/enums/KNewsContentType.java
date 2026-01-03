package core.global.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum KNewsContentType {
    K_POP, K_DRAMA, K_CULTURE, K_BEAUTY, K_FASHION;

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
