package core.global.entity.image.service;

import core.global.entity.image.dto.ImageDto;
import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ProfileImageService {

    // USER

    @Transactional
    void saveUserProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    String updateUserProfileImage(Long userId, String requestedKeyOrUrl);

    @Transactional
    void deleteUserProfileImage(Long userId);

    String getUserProfileKey(Long userId);

    // CHAT ROOM

    @Transactional
    void saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl);

    @Transactional
    String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl);

    @Transactional
    void deleteChatRoomProfileImage(Long chatRoomId);

    String getRoomImageUrl(Long roomId);

    List<ImageDto> findImagesForChatRooms(List<Long> roomIds);

    void uploadUserProfileImage(Long userId, MultipartFile file);
}