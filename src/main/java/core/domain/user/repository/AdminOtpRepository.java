package core.domain.user.repository;

import core.domain.user.entity.AdminOtp;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface AdminOtpRepository extends JpaRepository<AdminOtp, Long> {
    Optional<AdminOtp> findByUser(User user);
    @Modifying // 데이터 변경(삭제/수정) 작업 시 필수
    @Transactional
        // 삭제 작업이 안전하게 완료되도록 보장
    void deleteByUserId(Long userId);
}
