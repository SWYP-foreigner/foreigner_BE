package core.domain.admin.dto;

import core.domain.user.entity.User;
import core.global.enums.AiType;
import lombok.Getter;

import java.time.Instant;

@Getter
public class AiUserListDto {
    private Long id;
    private String name;
    private String language;
    private Instant createdAt;
    private AiType aiType;

    public AiUserListDto(User user, AiType aiType) {
        this.id = user.getId();
        this.name = user.getFirstName() + " " + user.getLastName();
        this.language = user.getLanguage();
        this.createdAt = user.getCreatedAt();
        this.aiType = aiType != null ? aiType : AiType.ALL;
    }
}
