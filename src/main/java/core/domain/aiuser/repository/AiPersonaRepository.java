package core.domain.aiuser.repository;

import core.domain.aiuser.entity.AiPersona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiPersonaRepository extends JpaRepository<AiPersona, Long> {
    Optional<AiPersona> findByUserId(Long userId);
    void deleteByUserId(Long userId);

}
