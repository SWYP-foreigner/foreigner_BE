package core.domain.user.dto;

import core.domain.user.entity.Follow;
import core.domain.user.entity.User;

public record FollowingInfoDto(
        Long targetUserId,
        String targetUserName,
        String targetUserEmail
) {
    public static FollowingInfoDto from(Follow follow) {
        User following = follow.getFollowing();
        return new FollowingInfoDto(
                following.getId(),
                following.getFirstName()+" "+following.getLastName(),
                following.getEmail()
        );
    }
}
