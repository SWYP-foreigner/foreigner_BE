package core.global.entity.image.service;

import core.domain.post.entity.Post;
import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ImageService {

    List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request);

//    @Transactional
//    void saveOrUpdatePostImages(Long postId,
//                                List<String> toAdd,
//                                List<String> toRemove);

    void savePostImages(Long postId, List<String> toAdd) throws BusinessException;

    void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove);

    void deleteObject(String keyOrUrl);

    void deleteFolder(String fileLocation);

    // ✅ 프로필 전담
    /** 요청 키(URL/키)를 검증하고 temp/*면 최종으로 이동하여 Image(USER, userId, order=0)로 upsert. 최종 key 반환 */
//    String upsertUserProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    String saveUserProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    String saveChatRoomProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    String updateUserProfileImage(Long userId, String requestedKeyOrUrl);

    /** 현재 프로필 이미지를 삭제(S3 + image 레코드) */
    void deleteUserProfileImage(Long userId);

    @Transactional
    String updateChatRoomProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    void deleteChatRoomProfileImage(Long userId);

    /** 현재 프로필 이미지 key 조회(없으면 null) */
    String getUserProfileKey(Long userId);

    String getRoomImageUrl(Long roomId);

    /** key 또는 URL을 내부 key로 정규화 */
    String normalizeKey(String keyOrUrl);

    /** 내부 key → 공개 URL */
    String toPublicUrl(String keyOrNull);
    List<ImageDto> findImagesForChatRooms(List<Long> roomIds);

    @Transactional
    void uploadAndSavePostImages(Post post, List<MultipartFile> images) throws IOException;
}
