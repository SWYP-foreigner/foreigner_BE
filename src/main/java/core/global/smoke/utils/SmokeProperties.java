package core.global.smoke.utils;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "smoke")
public class SmokeProperties {
    private boolean enabled = false;
    private String token;
    private String baseUrl;
    private List<Case> gate; // 배포용 핵심 체크
    private List<Case> full; // 관리자용 전체 체크 (추가)
    private AdminAuth admin;

    @Getter @Setter
    public static class Case {
        private String name;
        private String type;
        private String method;
        private String path;
        private int expectedStatus = 200;
    }

    @Getter @Setter
    public static class AdminAuth {
        private String email;
        private String password;
        private String loginPath; // 예: /api/v1/member/doLogin
    }
}
