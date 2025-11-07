package core.domain.user.dto;


/**
 * 유저 프로필 완료 여부 응답 레코드
 * @param userId 확인할 유저 ID
 * @param profileCompleted 프로필 완료 여부 (true/false)
 */
public record ProfileCompletionResponse(
        Long userId,
        boolean profileCompleted
) {
}