package core.domain.maincontent.entity;

import com.fasterxml.jackson.annotation.JsonValue;

public enum KNewsContentType {
    K_POP, K_DRAMA, K_CULTURE, K_BEAUTY, K_FASHION;

    @JsonValue
    public String getValue() {
        return name().replace("_", "-");
    }
}
