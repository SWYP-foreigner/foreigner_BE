package core.global.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 *
 * 로그인 완료시 애플 서버에서 받아와야 하는 정보
 */
@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AppleLoginResult {
    private AppleUserInfo user;
    private AppleTokenResponse tokens;
}
