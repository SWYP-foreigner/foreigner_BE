package core.domain.user.dto;

import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;

public record BlockedUserInfoDto(
        Long targetUserId,
        String targetUserName,
        String targetUserEmail
) {
    public static BlockedUserInfoDto from(BlockUser blockUser) {
        User blocked = blockUser.getBlocked();
        return new BlockedUserInfoDto(
                blocked.getId(),
                blocked.getName(),
                blocked.getEmail()
        );
    }
}
