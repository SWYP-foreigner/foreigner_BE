package core.global.dto;

public record LoginResponseDto(
        Long id,
        String accessToken,
        String refreshToken
) {
}