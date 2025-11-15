package core.domain.user.repository;

import core.domain.user.dto.UserSearchRequest;
import core.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepositoryCustom {
    Page<User> searchUsers(UserSearchRequest condition, Pageable pageable);
}
