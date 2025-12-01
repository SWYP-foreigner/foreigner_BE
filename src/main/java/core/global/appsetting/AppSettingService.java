package core.global.appsetting;

import core.global.enums.AppSettingKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    private final AppSettingRepository appSettingRepository;

    @Transactional(readOnly = true)
    public SupportLinksResponse getSupportLinks() {
        Map<String, String> settingsMap = appSettingRepository.findAll().stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));

        return SupportLinksResponse.builder()
                .feedbackUrl(settingsMap.getOrDefault(AppSettingKey.FEEDBACK_URL.getKey(), ""))
                .bugReportUrl(settingsMap.getOrDefault(AppSettingKey.BUG_REPORT_URL.getKey(), ""))
                .build();
    }
}