package core.global.search.dto;

import core.domain.post.entity.Post;

public record PostCreatedEvent(Post post) {
}
