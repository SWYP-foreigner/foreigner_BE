package core.global.metrics.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class UsageEvent {
    @JsonProperty("@timestamp")
    private Instant timestamp;  // ES 템플릿의 date 필드
    private String user_id;     // keyword
    private String event;       // keyword
    private String device;      // keyword
    private Map<String, Object> meta; // object(enabled:false)
}