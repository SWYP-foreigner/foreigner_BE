package core.global.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PerspectiveResponse(
        Map<String, AttributeScore> attributeScores
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttributeScore(SummaryScore summaryScore) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummaryScore(double value) {}
}
