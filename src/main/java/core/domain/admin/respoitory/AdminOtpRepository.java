package core.domain.admin.respoitory;

import core.domain.user.entity.AdminOtp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminOtpRepository extends JpaRepository<AdminOtp, Long> {
    void deleteAllByUserId(Long userId);
}