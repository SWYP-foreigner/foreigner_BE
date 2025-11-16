package core.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import core.domain.user.entity.User;

import java.time.Instant;

public record UserListResponse(
        Long userId,
        String email,
        String name,
        String country,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant createdAt
) {
    public static UserListResponse from(User user) {
        return new UserListResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName()+" "+user.getLastName(),
                user.getCountry(),
                user.getCreatedAt()
        );
    }
}
