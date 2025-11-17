package core.global.entity.image.service.impl;

import core.domain.post.entity.Post;
import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.entity.image.service.ImageService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.PostImageService;
import core.global.entity.image.service.ProfileImageService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private final PostImageService postImageService;
    private final ProfileImageService profileImageService;
    private final ImageStorageClient imageStorageClient;

    @Override
    public List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request) {
        return postImageService.generatePresignedUrls(request);
    }

    @Override
    @Transactional
    public void savePostImages(Long postId, List<String> toAdd) {
        postImageService.savePostImages(postId, toAdd);
    }

    @Override
    @Transactional
    public void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove) {
        postImageService.updatePostImages(postId, toAdd, toRemove);
    }

    @Override
    @Transactional
    public void saveUserProfileImage(Long userId, String requestedKeyOrUrl) {
        profileImageService.saveUserProfileImage(userId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public String updateUserProfileImage(Long userId, String requestedKeyOrUrl) {
        return profileImageService.updateUserProfileImage(userId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {
        profileImageService.deleteUserProfileImage(userId);
    }

    @Override
    @Transactional
    public void saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        profileImageService.saveChatRoomProfileImage(chatRoomId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        return profileImageService.updateChatRoomProfileImage(chatRoomId, requestedKeyOrUrl);
    }

    @Override
    @Transactional
    public void deleteChatRoomProfileImage(Long chatRoomId) {
        profileImageService.deleteChatRoomProfileImage(chatRoomId);
    }

    @Override
    public String getUserProfileKey(Long userId) {
        return profileImageService.getUserProfileKey(userId);
    }

    @Override
    public String getRoomImageUrl(Long roomId) {
        return profileImageService.getRoomImageUrl(roomId);
    }

    @Override
    public List<ImageDto> findImagesForChatRooms(List<Long> roomIds) {
        return profileImageService.findImagesForChatRooms(roomIds);
    }

    @Override
    @Transactional
    public void uploadAndSavePostImages(Post post, List<MultipartFile> multipartFiles) throws IOException {
        postImageService.uploadAndSavePostImages(post, multipartFiles);
    }

    @Override
    @Transactional
    public void deleteFolder(String fileLocation) {
        imageStorageClient.deleteFolder(fileLocation);
    }
}
