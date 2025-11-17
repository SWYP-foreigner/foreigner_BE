package core.global.entity.image.service;

import core.global.entity.image.dto.ImageDto;

import java.util.List;

public interface ProfileImageService {

    // USER
    String saveUserProfileImage(Long userId, String requestedKeyOrUrl);

    String updateUserProfileImage(Long userId, String requestedKeyOrUrl);

    void deleteUserProfileImage(Long userId);

    String getUserProfileKey(Long userId);

    // CHAT ROOM
    String saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl);

    String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl);

    void deleteChatRoomProfileImage(Long chatRoomId);

    String getRoomImageUrl(Long roomId);

    List<ImageDto> findImagesForChatRooms(List<Long> roomIds);
}