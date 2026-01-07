<<<<<<<< HEAD:src/main/java/core/domain/ai/repository/AiPersonaRepository.java
package core.domain.ai.repository;

import core.domain.ai.entity.AiPersona;
========
package core.domain.aiuser.repository;

import core.domain.aiuser.entity.AiPersona;
>>>>>>>> test:src/main/java/core/domain/aiuser/repository/AiPersonaRepository.java
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiPersonaRepository extends JpaRepository<AiPersona, Long> {
    Optional<AiPersona> findByUserId(Long userId);
    void deleteByUserId(Long userId);

}
