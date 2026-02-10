package core.domain.payment.dto;

import core.global.enums.payment.PurchaseStatus;

import java.time.Instant;

public record AppleTransactionInfo(
        String transactionId,
        String originalTransactionId,
        String productId,
        Instant purchaseDate,
        Instant expiresDate,
        PurchaseStatus status,
        String raw
) {}
