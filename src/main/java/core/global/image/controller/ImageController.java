package core.global.image.controller;

import core.global.image.dto.PresignRequest;
import core.global.image.dto.PresignResponse;
import core.global.image.service.NcpS3Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@Tag(name = "Image", description = "이미지 업로드 관련 API")
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

    private final NcpS3Service ncpS3Service;

    /**
     * 이미지 업로드를 위한 Presigned URL을 발급합니다.
     * 클라이언트는 이 URL을 사용하여 NCP S3에 직접 파일을 업로드합니다.
     * @param request 업로드할 이미지의 컨텐츠 타입과 파일 이름
     * @return Presigned URL과 key를 포함하는 응답 DTO
     */
    @PostMapping("/presign")
    @Operation(summary = "Presigned URL 발급", description = "클라이언트가 S3에 직접 이미지를 업로드하기 위한 URL을 발급합니다.")
    @ApiResponse(responseCode = "200", description = "URL 발급 성공")
    public ResponseEntity<PresignResponse> getPresignedUrl(@RequestBody PresignRequest request) {
        // 실제 애플리케이션에서는 인증 객체에서 userId를 가져옵니다.
        PresignResponse response = ncpS3Service.generatePresignedUrl(request.getFileName(),request.getType());
        return ResponseEntity.ok(response);
    }
}
