package core.domain.image.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PresignedUrlResponse {

    // NCP S3에 파일을 PUT 요청으로 업로드할 URL
    private String presignedUrl;

    // 업로드된 파일이 S3에 저장될 최종 경로
    private String filePath;
}
