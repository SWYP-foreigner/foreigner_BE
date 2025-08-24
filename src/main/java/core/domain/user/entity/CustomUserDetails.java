package core.domain.user.entity;


import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Getter
public class CustomUserDetails implements UserDetails, Serializable {

    private final Long id;
    private final String username;
    private final String password;   /**소셜 로그인이면 null 가능**/
    private final Collection<? extends GrantedAuthority> authorities;

    private final boolean accountNonExpired  = true;
    private final boolean accountNonLocked   = true;
    private final boolean credentialsNonExpired = true;
    private final boolean enabled            = true;

    private CustomUserDetails(Long id,
                              String username,
                              String password,
                              Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.authorities = authorities;
    }

    /** User 엔티티 -> CustomUserDetails 변환 */
    public static CustomUserDetails from(User user) {
        List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));

        return new CustomUserDetails(
                user.getId(),
                user.getUsername(),
                null,
                auths
        );
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return accountNonExpired; }
    @Override public boolean isAccountNonLocked() { return accountNonLocked; }
    @Override public boolean isCredentialsNonExpired() { return credentialsNonExpired; }
    @Override public boolean isEnabled() { return enabled; }

    // 동등성: 같은 userId면 동일 사용자로 간주
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomUserDetails that)) return false;
        return Objects.equals(id, that.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
}
