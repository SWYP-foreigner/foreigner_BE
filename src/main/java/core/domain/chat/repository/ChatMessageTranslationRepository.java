package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessageTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageTranslationRepository extends JpaRepository<ChatMessageTranslation, Long> {

    List<ChatMessageTranslation> findByMessageIdInAndLanguageCode(List<Long> messageIds, String languageCode);

    void deleteByCreatedAtBefore(java.time.Instant threshold);
}