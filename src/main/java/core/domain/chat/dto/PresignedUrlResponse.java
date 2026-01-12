package core.domain.chat.dto;

public record PresignedUrlResponse(
        String presignedUrl,    // 메인 파일(동영상/이미지) 업로드 URL
        String fileKey,         // 메인 파일 Key (DB 저장용)

        String thumbnailUrl,    // [추가] 썸네일 업로드 URL (이미지면 null)
        String thumbnailKey     // [추가] 썸네일 Key (이미지면 null)
) {}