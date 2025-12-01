package core.global.appsetting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    private final AppSettingRepository appSettingRepository;

    private static final String KEY_FEEDBACK = "FEEDBACK_URL";
    private static final String KEY_BUG = "BUG_REPORT_URL";

    @Transactional(readOnly = true)
    public SupportLinksResponse getSupportLinks() {
        Map<String, String> settingsMap = appSettingRepository.findAll().stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));

        return SupportLinksResponse.builder()
                .feedbackUrl(settingsMap.getOrDefault(KEY_FEEDBACK, ""))
                .bugReportUrl(settingsMap.getOrDefault(KEY_BUG, ""))
                .build();
    }
}