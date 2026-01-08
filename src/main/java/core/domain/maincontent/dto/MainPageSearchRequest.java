package core.domain.maincontent.dto;

import java.time.Instant;

public record MainPageSearchRequest(String q, Double afterScore, Instant afterTime, Long afterId, int limit) {
}
