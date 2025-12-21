package core.global.ai.repository;

import core.global.ai.entity.AiPersona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiPersonaRepository extends JpaRepository<AiPersona, Long> {
    Optional<AiPersona> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
