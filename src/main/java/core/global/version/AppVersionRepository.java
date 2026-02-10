package core.global.version;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
    Optional<AppVersion> findByPlatform(Platform platform);
}