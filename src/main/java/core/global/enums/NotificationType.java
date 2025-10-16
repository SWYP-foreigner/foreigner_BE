package core.global.enums;

import lombok.Getter;

@Getter
public enum NotificationType {
    post(true),
    comment(true),
    chat(true),
    follow(true),
    receive(true),
    newuser(false), // 👈 사용자가 설정할 수 없으므로 false
    followuserpost(true);

    private final boolean configurable;

    NotificationType(boolean configurable) {
        this.configurable = configurable;
    }


}