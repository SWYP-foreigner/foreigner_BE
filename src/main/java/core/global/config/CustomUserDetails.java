package core.global.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
// import lombok.NoArgsConstructor; // 👈 제거
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Collections; // 추가

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomUserDetails extends User {

    private Long userId;

    public CustomUserDetails() {
        super("anonymous", "", Collections.emptyList());
        this.userId = 0L;
    }

    public CustomUserDetails(
            Long userId,
            String username,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(username, "", authorities);
        this.userId = userId;
    }
}