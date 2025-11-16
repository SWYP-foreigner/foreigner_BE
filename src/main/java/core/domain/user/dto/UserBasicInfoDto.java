package core.domain.user.dto;

import core.domain.user.entity.User;

import java.time.Instant;

public record UserBasicInfoDto(
        Long userId,
        String name,
        String email,
        String country,
        String introduction,
        Instant createdAt,
        Instant lastSeenAt
) {
    public static UserBasicInfoDto from(User user) {
        return new UserBasicInfoDto(
                user.getId(),
                user.getFirstName()+" "+user.getLastName(),
                user.getEmail(),
                user.getCountry(),
                user.getIntroduction(),
                user.getCreatedAt(),
                user.getLastSeenAt()
        );
    }
}