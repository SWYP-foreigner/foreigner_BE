package core.global.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserLanguageDTO {

    // 사용자가 선택한 언어 코드 (예: "en", "ko")
    private String language;
}