package core.global.appsetting;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SupportLinksResponse {
    private String feedbackUrl;
    private String bugReportUrl;
}