package core.global.image.controller;

import core.global.dto.ApiResponse;
import core.global.image.dto.ImageDto;
import core.global.image.dto.PresignedUrlRequest;
import core.global.image.dto.PresignedUrlResponse;
import core.global.image.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Image", description = "이미지 업로드 관련 API")
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @Operation(summary = "다건 Presigned URL 발급",
            description = "uploadSessionId + files[] 기반으로 Presigned URL을 일괄 발급")
    @PostMapping("/presign")
    public ResponseEntity<ApiResponse<List<PresignedUrlResponse>>> getPresignedUrls(
            @RequestBody PresignedUrlRequest request
    ) {
        List<PresignedUrlResponse> list =
                imageService.generatePresignedUrls( request);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @DeleteMapping("/object")
    public ResponseEntity<ApiResponse<Void>> deleteObjectByKey(@RequestParam String keyOrUrl) {
        imageService.deleteObject(keyOrUrl);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/delete-folder")
    public ResponseEntity<ApiResponse<Void>> deleteFolder(@RequestParam String fileLocation) {
        imageService.deleteFolder(fileLocation);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    /**
     * 여러 채팅방 ID에 해당하는 이미지 정보들을 일괄 조회합니다.
     * @param roomIds 이미지 정보를 조회할 채팅방 ID 리스트
     * @return 각 채팅방에 대한 ImageDto 리스트
     */
    @GetMapping("/chat-rooms")
    public ResponseEntity<List<ImageDto>> getImagesForChatRooms(@RequestParam("roomIds") List<Long> roomIds) {
        List<ImageDto> images = imageService.findImagesForChatRooms(roomIds);
        return ResponseEntity.ok(images);
    }

}
