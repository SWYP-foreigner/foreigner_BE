package core.domain.image.service;

import core.domain.image.dto.PresignedUrlResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageService {

    private final NcpS3Service ncpS3Service;
    // 테스트용이므로 userRepository는 주입하지 않았습니다.

    /**
     * 클라이언트에게 파일 업로드를 위한 Presigned URL을 발급합니다. (테스트용)
     * @param fileName 업로드할 파일 이름
     * @return Presigned URL과 이미지 파일 경로를 담은 DTO
     */
    @Transactional(readOnly = true)
    public PresignedUrlResponse getPresignedUrl(String fileName) {
        // userId를 통한 사용자 존재 여부 확인 로직을 제거했습니다.
        return ncpS3Service.generatePresignedUrl(fileName);
    }
}
