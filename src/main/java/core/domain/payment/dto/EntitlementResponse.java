package core.domain.payment.dto;

import core.domain.payment.entity.IapEntitlement;

import java.time.Instant;

public record EntitlementResponse(
        Long userId,
        boolean active,
        String feature,
        String tier,
        Instant expiresAt,
        String source,
        String status
) {
    public static EntitlementResponse fromEntity(IapEntitlement e) {
        return new EntitlementResponse(
                e.getUserId(),
                e.isActive(),
                e.getFeature(),
                e.getTier(),
                e.getExpiresAt(),
                e.getSource() != null ? e.getSource().name() : null,
                e.getStatus() != null ? e.getStatus().name() : null
        );
    }
}