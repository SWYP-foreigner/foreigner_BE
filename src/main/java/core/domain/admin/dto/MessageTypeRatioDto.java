package core.domain.admin.dto;

public record MessageTypeRatioDto(
        long groupMsgCount,
        long privateMsgCount,
        long totalMsgCount,
        double groupRatio,
        double privateRatio
) {}
