package core.domain.admin.dto;

import core.domain.user.dto.CountryRetentionDto;
import core.domain.user.dto.StringCountDto;
import core.global.dto.*;

import java.util.List;

public record AdminMetricsDto(
        InactiveUserStatsDto userStats,
        UserActivityBucketsDto activityBuckets,
        List<WeeklyCohortDto> weeklyCohorts,
        AdvancedMetricsDto advancedMetrics,
        List<StringCountDto> countryStats,
        List<StringCountDto> languageStats,
        List<CountryRetentionDto> countryRetentions,
        MessageTypeRatioDto messageTypeRatio,
        ProfilePhotoStatsDto profilePhotoStats,
        FirstMessageTimeDto firstMessageTimeStats,
        DemographicsDto demographics,
        GhostUserStatsDto ghostUserStats,
        ReplyTimeStatsDto replyTimeStats,
        ChatRoomHealthDto chatRoomHealth
) {}
