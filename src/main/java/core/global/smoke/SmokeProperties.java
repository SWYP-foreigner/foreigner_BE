package core.global.smoke;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "smoke")
public class SmokeProperties {
    private boolean enabled = false;
    private String token;
    private String baseUrl;
    private List<Case> gate;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<Case> getGate() { return gate; }
    public void setGate(List<Case> gate) { this.gate = gate; }

    public static class Case {
        private String name;
        private String method;
        private String path;
        private int expectedStatus = 200;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public int getExpectedStatus() { return expectedStatus; }
        public void setExpectedStatus(int expectedStatus) { this.expectedStatus = expectedStatus; }
    }
}
