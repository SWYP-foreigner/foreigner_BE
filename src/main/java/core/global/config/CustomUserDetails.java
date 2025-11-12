package core.global.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Collections;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomUserDetails extends User {

    private Long userId;

    /**
     * @brief Jackson 역직렬화를 위한 (임시) 기본 생성자
     * @apiNote 부모 클래스(User)는 기본 생성자가 없으므로,
     * 임의의 값으로 부모 생성자를 명시적으로 호출해야 합니다.
     */
    public CustomUserDetails() {
        super("default-username", "default-password", Collections.emptyList());
        this.userId = -1L; // 임의의 기본값
    }

    /**
     * @brief 실제 인증 로직에서 사용하는 생성자
     */
    public CustomUserDetails(
            Long userId,
            String username,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(username, "", authorities); // 실제로는 패스워드를 비웁니다.
        this.userId = userId;
    }
}