package core.domain.chat.repository;

import core.domain.chat.entity.ChatMessageTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageTranslationRepository extends JpaRepository<ChatMessageTranslation, Long> {

    List<ChatMessageTranslation> findByMessageIdInAndLanguageCode(List<Long> messageIds, String languageCode);
    @Modifying
    @Query(value = """
        INSERT INTO chat_message_translation (message_id, language_code, content)
        VALUES (:messageId, :languageCode, :content)
        ON CONFLICT (message_id, language_code) DO NOTHING
        """, nativeQuery = true)
    void saveIgnoreDuplicate(@Param("messageId") Long messageId,
                             @Param("languageCode") String languageCode,
                             @Param("content") String content);
    void deleteByCreatedAtBefore(java.time.Instant threshold);
}