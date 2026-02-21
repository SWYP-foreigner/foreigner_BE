package core.domain.user.repository;

import core.domain.user.entity.AdminOtp;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminOtpRepository extends JpaRepository<AdminOtp, Long> {
    Optional<AdminOtp> findByUser(User user);
    void deleteAllByUserId(Long userId);
}
