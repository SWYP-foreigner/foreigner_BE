package core.global.apple.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Apple /auth/token API의 응답을 담을 DTO
public record AppleRefreshTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("refresh_token") String refreshToken, // 👈 우리가 필요한 것
        @JsonProperty("token_type") String tokenType
) {}